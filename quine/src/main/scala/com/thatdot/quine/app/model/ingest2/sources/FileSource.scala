package com.thatdot.quine.app.model.ingest2.sources

import java.nio.charset.Charset
import java.nio.file.Paths

import org.apache.pekko.NotUsed
import org.apache.pekko.http.scaladsl.common.EntityStreamingSupport
import org.apache.pekko.stream.scaladsl.{Flow, Framing, Source}
import org.apache.pekko.util.ByteString

import cats.data.ValidatedNel
import cats.implicits.catsSyntaxValidatedId
import com.typesafe.scalalogging.LazyLogging

import com.thatdot.common.logging.Log.LogConfig
import com.thatdot.data.DataFoldableFrom
import com.thatdot.quine.app.ShutdownSwitch
import com.thatdot.quine.app.model.ingest.NamedPipeSource
import com.thatdot.quine.app.model.ingest.serialization.ContentDecoder
import com.thatdot.quine.app.model.ingest2.V2IngestEntities.FileFormat
import com.thatdot.quine.app.model.ingest2.codec.{CypherStringDecoder, FrameDecoder, JsonDecoder}
import com.thatdot.quine.app.model.ingest2.source.{DecodedSource, FramedSource, IngestBounds}
import com.thatdot.quine.app.routes.IngestMeter
import com.thatdot.quine.routes.FileIngestMode
import com.thatdot.quine.util.BaseError

/** Build a framed source from a file-like stream of ByteStrings. In practice this
  * means a finite, non-streaming source: File sources, S3 file sources, and std ingest.
  *
  * This framing provides
  * - ingest bounds
  * - char encoding
  * - compression
  * - record delimit sizing
  * - metering
  *
  * so these capabilities should not be applied to the provided src stream.
  */
case class FramedFileSource(
  src: Source[ByteString, NotUsed],
  charset: Charset = DEFAULT_CHARSET,
  delimiterFlow: Flow[ByteString, ByteString, NotUsed],
  ingestBounds: IngestBounds = IngestBounds(),
  decoders: Seq[ContentDecoder] = Seq(),
  ingestMeter: IngestMeter,
) {

  val source: Source[ByteString, NotUsed] =
    src
      .via(decompressingFlow(decoders))
      .via(transcodingFlow(charset))
      .via(delimiterFlow) // TODO note this will not properly delimit streaming binary formats (e.g. protobuf)
      //Note: bounding is applied _after_ delimiter.
      .via(boundingFlow(ingestBounds))
      .via(metered(ingestMeter, _.size))

  private def framedSource: FramedSource =
    new FramedSource {

      type SrcFrame = ByteString
      val stream: Source[SrcFrame, ShutdownSwitch] = withKillSwitches(source)
      val meter: IngestMeter = ingestMeter

      def content(input: SrcFrame): Array[Byte] = input.toArrayUnsafe()
      val foldableFrame: DataFoldableFrom[SrcFrame] = DataFoldableFrom.byteStringDataFoldable

    }

  def decodedSource[A](decoder: FrameDecoder[A]): DecodedSource = framedSource.toDecoded(decoder)

}

object FileSource extends LazyLogging {

  private def jsonDelimitingFlow(maximumLineSize: Int): Flow[ByteString, ByteString, NotUsed] =
    EntityStreamingSupport.json(maximumLineSize).framingDecoder

  private def lineDelimitingFlow(maximumLineSize: Int): Flow[ByteString, ByteString, NotUsed] = Framing
    .delimiter(ByteString("\n"), maximumLineSize, allowTruncation = true)
    .map(line => if (!line.isEmpty && line.last == '\r') line.dropRight(1) else line)

  def srcFromIngest(path: String, fileIngestMode: Option[FileIngestMode])(implicit
    logConfig: LogConfig,
  ): Source[ByteString, NotUsed] =
    NamedPipeSource.fileOrNamedPipeSource(Paths.get(path), fileIngestMode)

  def decodedSourceFromFileStream(
    fileSource: Source[ByteString, NotUsed],
    format: FileFormat,
    charset: Charset,
    maximumLineSize: Int,
    bounds: IngestBounds = IngestBounds(),
    meter: IngestMeter,
    decoders: Seq[ContentDecoder] = Seq(),
  ): ValidatedNel[BaseError, DecodedSource] =
    format match {
      case FileFormat.LineFormat =>
        FramedFileSource(
          fileSource,
          charset,
          lineDelimitingFlow(maximumLineSize),
          bounds,
          decoders,
          meter,
        ).decodedSource(CypherStringDecoder).valid
      case FileFormat.JsonFormat =>
        FramedFileSource(
          fileSource,
          charset,
          jsonDelimitingFlow(maximumLineSize),
          bounds,
          decoders,
          meter,
        ).decodedSource(JsonDecoder).valid

      case FileFormat.CsvFormat(headers, delimiter, quoteChar, escapeChar) =>
        CsvFileSource(
          fileSource,
          bounds,
          meter,
          headers,
          charset,
          delimiter.byte,
          quoteChar.byte,
          escapeChar.byte,
          maximumLineSize,
          decoders,
        ).decodedSource.valid

    }
}
