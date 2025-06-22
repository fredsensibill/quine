package com.thatdot.quine.app.model.ingest2.sources

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.http.scaladsl.model.sse.ServerSentEvent
import org.apache.pekko.stream.connectors.sse.scaladsl.EventSource
import org.apache.pekko.stream.scaladsl.Source

import cats.data.ValidatedNel
import cats.implicits.catsSyntaxValidatedId

import com.thatdot.data.{DataFoldableFrom, DataFolderTo}
import com.thatdot.quine.app.ShutdownSwitch
import com.thatdot.quine.app.model.ingest.serialization.ContentDecoder
import com.thatdot.quine.app.model.ingest2.source.FramedSource
import com.thatdot.quine.app.routes.IngestMeter
import com.thatdot.quine.util.BaseError

case class ServerSentEventSource(url: String, meter: IngestMeter, decoders: Seq[ContentDecoder] = Seq())(implicit
  val system: ActorSystem,
) extends FramedSourceProvider {

  def stream: Source[ServerSentEvent, ShutdownSwitch] =
    withKillSwitches(
      EventSource(uri = Uri(url), send = Http().singleRequest(_))
        .via(metered[ServerSentEvent](meter, e => e.data.length)),
    )

  private val serverSentEventFolder: DataFoldableFrom[ServerSentEvent] = new DataFoldableFrom[ServerSentEvent] {
    def fold[B](value: ServerSentEvent, folder: DataFolderTo[B]): B = {
      val builder = folder.mapBuilder()

      builder.add("data", folder.string(value.data))
      value.id.foreach(id => builder.add("id", folder.string(id)))
      value.retry.foreach(retry => builder.add("retry", folder.integer(retry.toLong)))
      value.eventType.foreach(eventType => builder.add("eventType", folder.string(eventType)))

      builder.finish()
    }
  }
  def framedSource: ValidatedNel[BaseError, FramedSource] =
    FramedSource[ServerSentEvent](
      stream,
      meter,
      ssEvent => ContentDecoder.decode(decoders, ssEvent.data.getBytes()),
      serverSentEventFolder,
    ).valid

}
