package com.thatdot.quine.app.model.ingest

import scala.concurrent.Future
import scala.util.{Success, Try}

import org.apache.pekko.stream.connectors.sqs.scaladsl.{SqsAckSink, SqsSource}
import org.apache.pekko.stream.connectors.sqs.{MessageAction, SqsSourceSettings}
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import org.apache.pekko.{Done, NotUsed}

import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.Message

import com.thatdot.common.logging.Log.LogConfig
import com.thatdot.quine.app.model.ingest.serialization.{ContentDecoder, ImportFormat}
import com.thatdot.quine.app.model.ingest.util.AwsOps
import com.thatdot.quine.app.model.ingest.util.AwsOps.AwsBuilderOps
import com.thatdot.quine.graph.MasterStream.IngestSrcExecToken
import com.thatdot.quine.graph.cypher.Value
import com.thatdot.quine.graph.{CypherOpsGraph, NamespaceId}
import com.thatdot.quine.routes.{AwsCredentials, AwsRegion}
import com.thatdot.quine.util.SwitchMode

/** The definition of an incoming AWS SQS stream.
  *
  * @param name               the unique, human-facing name of the ingest stream
  * @param queueURL           the URL of the SQS queue from which to read
  * @param format             the [[ImportFormat]] to use in deserializing and writing records from the queue
  * @param initialSwitchMode  is the ingest stream initially paused or not?
  * @param readParallelism    how many records to pull off the SQS queue at a time
  * @param writeParallelism   how many records to write to the graph at a time
  * @param credentialsOpt     the AWS credentials necessary to access the provided SQS queue
  * @param deleteReadMessages if true, issue an acknowledgement for each successfully-deserialized message,
  *                           causing SQS to delete that message from the queue
  */
final case class SqsStreamSrcDef(
  override val name: String,
  override val intoNamespace: NamespaceId,
  queueURL: String,
  format: ImportFormat,
  initialSwitchMode: SwitchMode,
  readParallelism: Int,
  writeParallelism: Int,
  credentialsOpt: Option[AwsCredentials],
  regionOpt: Option[AwsRegion],
  deleteReadMessages: Boolean,
  maxPerSecond: Option[Int],
  decoders: Seq[ContentDecoder],
)(implicit val graph: CypherOpsGraph, protected val logConfig: LogConfig)
    extends RawValuesIngestSrcDef(
      format,
      initialSwitchMode,
      writeParallelism,
      maxPerSecond,
      decoders,
      s"$name (SQS ingest)",
      intoNamespace,
    ) {

  type InputType = Message

  implicit val client: SqsAsyncClient = SqsAsyncClient
    .builder()
    .credentials(credentialsOpt)
    .region(regionOpt)
    .httpClient(
      NettyNioAsyncHttpClient.builder.maxConcurrency(AwsOps.httpConcurrencyPerClient).build(),
    )
    .build()

  graph.system.registerOnTermination(client.close())

  override val ingestToken: IngestSrcExecToken = IngestSrcExecToken(s"$name: $queueURL")

  def source(): Source[Message, NotUsed] =
    SqsSource(queueURL, SqsSourceSettings().withParallelRequests(readParallelism))

  def rawBytes(message: Message): Array[Byte] = message.body.getBytes

  /** For each element, executes the MessageAction specified, and if a Deserialized body is present, returns it.
    *
    * This sends an "ignore" message for messages that fail on deserialization. It's not clear if that's the
    * correct thing to do, but leaving it in for now as it's what the pre-existing code did.
    */
  override val ack: Flow[TryDeserialized, Done, NotUsed] = if (deleteReadMessages) {
    val ackSink: Sink[(Try[Value], Message), Future[Done]] = SqsAckSink(queueURL)
      .contramap[TryDeserialized] {
        case (Success(_), msg) => MessageAction.delete(msg)
        case (_, msg) => MessageAction.ignore(msg)
      }
      .named("sqs-ack-sink")
    Flow[TryDeserialized].alsoTo(ackSink).map(_ => Done.done())
  } else {
    Flow[TryDeserialized].map(_ => Done.done())
  }

}
