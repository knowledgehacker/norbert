package com.linkedin.norbert
package network
package common

import logging.Logging
import actors.DaemonActor
import collection.immutable.SortedMap
import jmx.{RequestTimeTracker, RequestsPerSecondTracker}
import util.{Clock, ClockComponent}

class NetworkStatisticsActor[GroupIdType, RequestIdType](clock: Clock, timeWindow: Long = 1000L)(implicit ordering: Ordering[GroupIdType]) extends DaemonActor with Logging {
  object Stats {
    case class BeginRequest(groupId: GroupIdType, requestId: RequestIdType)
    case class EndRequest(groupId: GroupIdType, requestId: RequestIdType)

    case object GetProcessingStatistics

    case class ProcessingEntry(pendingTime: Long, pendingSize: Int, completedTime: Long, completedSize: Int)
    case class ProcessingStatistics(map: Map[GroupIdType, ProcessingEntry])

    case object GetRequestsPerSecond
    case class RequestsPerSecond(rps: Int)

    def average(total: Long, size: Int) = if(size == 0) 0.0 else total.toDouble / size

    def average(map: Map[GroupIdType, ProcessingEntry])( timeFn: (ProcessingEntry => Long))( sizeFn: (ProcessingEntry => Int)): Double = {
      val (total, size) = map.values.foldLeft((0L, 0)) { case ((t, s), stats) =>
          (t + timeFn(stats), s + sizeFn(stats))
        }
      average(total, size)
    }
  }

  private val timeTrackers = collection.mutable.Map.empty[GroupIdType, RequestTimeTracker[RequestIdType]]

  private val rps = new RequestsPerSecondTracker

  def act() = {
    import Stats._

    loop {
      react {
        case BeginRequest(groupId, requestId) =>
          val tracker = timeTrackers.getOrElseUpdate(groupId, new RequestTimeTracker(clock, timeWindow))
          tracker.beginRequest(requestId)

        case EndRequest(groupId, requestId) =>
          val tracker = timeTrackers.get(groupId).foreach { _.endRequest(requestId) }
          rps++

        case GetProcessingStatistics =>
          reply(ProcessingStatistics(timeTrackers.map { case (groupId, tracker) =>
            (groupId, ProcessingEntry(tracker.pendingRequestTimeTracker.total, tracker.pendingRequestTimeTracker.size, tracker.finishedRequestTimeTracker.total, tracker.finishedRequestTimeTracker.size))
          }.toMap))

        case GetRequestsPerSecond => reply(RequestsPerSecond(rps.rps))

        case msg => log.error("NetworkStatistics actor got invalid message: %s".format(msg))
      }
    }
  }
}