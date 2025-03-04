package com.thatdot.quine.persistor

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source

import cats.data.NonEmptyList
import com.google.common.hash.{BloomFilter, Funnel, PrimitiveSink}

import com.thatdot.common.logging.Log.{LogConfig, Safe, SafeLoggableInterpolator}
import com.thatdot.common.quineid.QuineId
import com.thatdot.quine.graph.cypher.quinepattern.QueryPlan
import com.thatdot.quine.graph.{
  BaseGraph,
  DomainIndexEvent,
  EventTime,
  MultipleValuesStandingQueryPartId,
  NamespaceId,
  NodeChangeEvent,
  NodeEvent,
  StandingQueryId,
  StandingQueryInfo,
}
import com.thatdot.quine.model.DomainGraphNode.DomainGraphNodeId
import com.thatdot.quine.util.Log.implicits._

// This needs to be serializable for the bloom filter to be serializable
case object QuineIdFunnel extends Funnel[QuineId] {
  override def funnel(from: QuineId, into: PrimitiveSink): Unit = {
    into.putBytes(from.array)
    ()
  }
}

object BloomFilteredPersistor {
  def maybeBloomFilter(
    maybeSize: Option[Long],
    persistor: NamespacedPersistenceAgent,
    persistenceConfig: PersistenceConfig,
  )(implicit
    materializer: Materializer,
    logConfig: LogConfig,
  ): NamespacedPersistenceAgent =
    maybeSize.fold(persistor)(new BloomFilteredPersistor(persistor, _, persistenceConfig))
}

/** [[NamespacedPersistenceAgent]] wrapper that short-circuits read calls to[[getNodeChangeEventsWithTime]],
  * [[getLatestSnapshot]], and [[getMultipleValuesStandingQueryStates]] regarding
  * QuineIds assigned to this position that the persistor knows not to exist with empty results.
  *
  * @param wrappedPersistor The persistor implementation to wrap
  * @param bloomFilterSize The number of expected nodes
  * @param falsePositiveRate The false positive probability
  */
private class BloomFilteredPersistor(
  wrappedPersistor: NamespacedPersistenceAgent,
  bloomFilterSize: Long,
  val persistenceConfig: PersistenceConfig,
  falsePositiveRate: Double = 0.1,
)(implicit materializer: Materializer, logConfig: LogConfig)
    extends WrappedPersistenceAgent(wrappedPersistor) {

  val namespace: NamespaceId = wrappedPersistor.namespace

  private val bloomFilter: BloomFilter[QuineId] =
    BloomFilter.create[QuineId](QuineIdFunnel, bloomFilterSize, falsePositiveRate)

  logger.info(safe"Initialized persistor bloom filter with size: ${Safe(bloomFilterSize)} records")

  @volatile private var mightContain: QuineId => Boolean = (_: QuineId) => true

  override def emptyOfQuineData(): Future[Boolean] =
    // TODO if bloomFilter.approximateElementCount() == 0 and the bloom filter is the only violation, that's also fine
    wrappedPersistor.emptyOfQuineData()

  def persistNodeChangeEvents(id: QuineId, events: NonEmptyList[NodeEvent.WithTime[NodeChangeEvent]]): Future[Unit] = {
    bloomFilter.put(id)
    wrappedPersistor.persistNodeChangeEvents(id, events)
  }

  def persistDomainIndexEvents(
    id: QuineId,
    events: NonEmptyList[NodeEvent.WithTime[DomainIndexEvent]],
  ): Future[Unit] = {
    bloomFilter.put(id)
    wrappedPersistor.persistDomainIndexEvents(id, events)
  }

  def getNodeChangeEventsWithTime(
    id: QuineId,
    startingAt: EventTime,
    endingAt: EventTime,
  ): Future[Iterable[NodeEvent.WithTime[NodeChangeEvent]]] =
    if (mightContain(id))
      wrappedPersistor.getNodeChangeEventsWithTime(id, startingAt, endingAt)
    else
      Future.successful(Iterable.empty)

  def getDomainIndexEventsWithTime(
    id: QuineId,
    startingAt: EventTime,
    endingAt: EventTime,
  ): Future[Iterable[NodeEvent.WithTime[DomainIndexEvent]]] =
    if (mightContain(id))
      wrappedPersistor.getDomainIndexEventsWithTime(id, startingAt, endingAt)
    else
      Future.successful(Iterable.empty)

  override def enumerateJournalNodeIds(): Source[QuineId, NotUsed] = wrappedPersistor.enumerateJournalNodeIds()

  override def enumerateSnapshotNodeIds(): Source[QuineId, NotUsed] = wrappedPersistor.enumerateSnapshotNodeIds()

  override def persistSnapshot(id: QuineId, atTime: EventTime, state: Array[Byte]): Future[Unit] = {
    bloomFilter.put(id)
    wrappedPersistor.persistSnapshot(id, atTime, state)
  }

  override def getLatestSnapshot(id: QuineId, upToTime: EventTime): Future[Option[Array[Byte]]] =
    if (mightContain(id))
      wrappedPersistor.getLatestSnapshot(id, upToTime)
    else
      Future.successful(None)

  override def persistStandingQuery(standingQuery: StandingQueryInfo): Future[Unit] =
    wrappedPersistor.persistStandingQuery(standingQuery)

  override def removeStandingQuery(standingQuery: StandingQueryInfo): Future[Unit] =
    wrappedPersistor.removeStandingQuery(standingQuery)

  override def getStandingQueries: Future[List[StandingQueryInfo]] = wrappedPersistor.getStandingQueries

  override def getMultipleValuesStandingQueryStates(
    id: QuineId,
  ): Future[Map[(StandingQueryId, MultipleValuesStandingQueryPartId), Array[Byte]]] =
    if (mightContain(id))
      wrappedPersistor.getMultipleValuesStandingQueryStates(id)
    else
      Future.successful(Map.empty)

  override def setMultipleValuesStandingQueryState(
    standingQuery: StandingQueryId,
    id: QuineId,
    standingQueryId: MultipleValuesStandingQueryPartId,
    state: Option[Array[Byte]],
  ): Future[Unit] = {
    bloomFilter.put(id)
    wrappedPersistor.setMultipleValuesStandingQueryState(standingQuery, id, standingQueryId, state)
  }

  override def persistQueryPlan(standingQueryId: StandingQueryId, qp: QueryPlan): Future[Unit] =
    wrappedPersistor.persistQueryPlan(standingQueryId, qp)

  override def deleteSnapshots(qid: QuineId): Future[Unit] = wrappedPersistor.deleteSnapshots(qid)

  override def deleteNodeChangeEvents(qid: QuineId): Future[Unit] = wrappedPersistor.deleteNodeChangeEvents(qid)

  override def deleteDomainIndexEvents(qid: QuineId): Future[Unit] = wrappedPersistor.deleteDomainIndexEvents(qid)

  override def deleteMultipleValuesStandingQueryStates(id: QuineId): Future[Unit] =
    wrappedPersistor.deleteMultipleValuesStandingQueryStates(id)

  /** Begins asynchronously loading all node ID into the bloom filter set.
    */
  override def declareReady(graph: BaseGraph): Unit = {
    val t0 = System.currentTimeMillis
    val source =
      if (persistenceConfig.journalEnabled) enumerateJournalNodeIds()
      else enumerateSnapshotNodeIds()
    val filteredSource = source.filter(graph.isLocalGraphNode)
    filteredSource
      .runForeach { q => // TODO consider using Sink.foreachAsync instead
        bloomFilter.put(q)
        ()
      }
      .onComplete {
        case Success(_) =>
          val d = System.currentTimeMillis - t0
          val c = bloomFilter.approximateElementCount()
          logger.info(safe"Finished loading in duration: ${Safe(d)} ms; node set size ~ ${Safe(c)} QuineIDs)")
          mightContain = bloomFilter.mightContain
        case Failure(ex) =>
          logger.warn(log"Error loading; continuing to run in degraded state" withException ex)
      }(ExecutionContext.parasitic)
    ()
  }

  override def deleteDomainIndexEventsByDgnId(dgnId: DomainGraphNodeId): Future[Unit] =
    wrappedPersistor.deleteDomainIndexEventsByDgnId(dgnId)

  override def shutdown(): Future[Unit] =
    wrappedPersistor.shutdown()

  override def delete(): Future[Unit] =
    wrappedPersistor.delete()

  def containsMultipleValuesStates(): Future[Boolean] = wrappedPersistor.containsMultipleValuesStates()
}
