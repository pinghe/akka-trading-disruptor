package org.samples.trading.akka

import org.junit._
import Assert._

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import org.samples.trading.domain._
import org.samples.trading.common._

import se.scalablesolutions.akka.actor.ActorRef
import se.scalablesolutions.akka.actor.Actor
import se.scalablesolutions.akka.actor.Actor.actorOf
import se.scalablesolutions.akka.dispatch.Dispatchers

class AkkaPerformanceTest extends PerformanceTest {
  type TS = AkkaTradingSystem
  type OR = ActorRef

  override def createTradingSystem: TS = new AkkaTradingSystem

  override
  def placeOrder(orderReceiver: ActorRef, order: Order): Rsp = {
    val r = orderReceiver !! order
    val rsp = r.getOrElse(new Rsp(false))
    rsp
  }


  // need this so that junit will detect this as a test case
  @Test
  def dummy {}

  override
  def runScenario(scenario: String, orders: List[Order], repeat: Int, numberOfClients: Int, delayMs: Int) = {
    val totalNumberOfRequests = orders.size * repeat * numberOfClients
    val latch = new CountDownLatch(numberOfClients)
    val receivers = tradingSystem.orderReceivers.toIndexedSeq
    val clients = (for (i <- 0 until numberOfClients) yield {
      val receiver = receivers(i % receivers.size)
      actorOf(new Client(receiver, orders, latch, repeat, delayMs))
    }).toList

    clients.foreach(_.start)
    val start = System.nanoTime
    clients.foreach(_ ! "run")
    val ok = latch.await(5000 + (2 + delayMs) * totalNumberOfRequests, TimeUnit.MILLISECONDS)
    val durationNs = (System.nanoTime - start)

    assertTrue(ok)
    assertEquals(numberOfClients * (orders.size / 2) * repeat, TotalTradeCounter.counter.get)
    logMeasurement(scenario, numberOfClients, durationNs, repeat, totalNumberOfRequests, averageRspTimeNs(clients), maxRspTimeNs(clients))
    clients.foreach(_.stop)
  }

  private def averageRspTimeNs(clients: List[ActorRef]) = {
    var totalDuration = 0L
    var totalNumberOfRequests = 0L
    for (each <- clients) {
      val rsp: Option[Metrics] = each !! "metrics"
      rsp.foreach(totalDuration += _.totalRspTimeNs)
      rsp.foreach(totalNumberOfRequests += _.numberOfRequests)
    }
    totalDuration / totalNumberOfRequests
  }

  private def maxRspTimeNs(clients: List[ActorRef]) = {
    var max = 0L
    for (each <- clients) {
      val rsp: Option[Metrics] = each !! "metrics"
      if (rsp != None) {
        if (rsp.get.maxRspTimeNs > max) max = rsp.get.maxRspTimeNs
      }
    }
    max
  }

  class Client(orderReceiver: ActorRef, orders: List[Order], latch: CountDownLatch, repeat: Int, delayMs: Int) extends Actor {
    val numberOfRequests = orders.size * repeat
    val metrics = new Metrics(numberOfRequests, 0, 0);

    self.dispatcher = Dispatchers.newExecutorBasedEventDrivenDispatcher("client-dispatcher")


    def this(orderReceiver: ActorRef, orders: List[Order], latch: CountDownLatch, repeat: Int) {
      this (orderReceiver, orders, latch, repeat, 0)
    }

    def receive = {
      case "metrics" => reply(metrics)
      case "run" =>
        (1 to repeat).foreach(i =>
          {
            //						println("Client repeat: " + i)
            for (o <- orders) {
              val t0 = System.nanoTime
              val rsp = placeOrder(orderReceiver, o)
              val duration = System.nanoTime - t0
              metrics.totalRspTimeNs += duration
              if (!rsp.status) {
                throw new IllegalStateException("Invalid rsp")
              }
              if (duration > metrics.maxRspTimeNs) metrics.maxRspTimeNs = duration
              delay(delayMs)
            }
          }
          )
        latch.countDown()

    }
  }

}


