package org.samples.trading.actor

import org.samples.trading.common._

import org.samples.trading.domain.Orderbook
import org.samples.trading.domain.StandbyTradeObserver
import scala.actors.Actor

class ActorTradingSystem extends TradingSystem {
  type ME = ActorMatchingEngine
  type OR = ActorOrderReceiver

  override def createMatchingEngines = {
    var i = 0
    val pairs =
      for (orderbooks: List[Orderbook] <- orderbooksGroupedByMatchingEngine)
      yield {
        i = i + 1
        val me = new ActorMatchingEngine("ME" + i, orderbooks)
        val orderbooksCopy = orderbooks map (o => new Orderbook(o.symbol) with StandbyTradeObserver)
        val standbyOption =
          if (useStandByEngines) {
            val meStandby = new ActorMatchingEngine("ME" + i + "s", orderbooksCopy)
            Some(meStandby)
          } else {
            None
          }
  
        (me, standbyOption)
      }

    Map() ++ pairs;
  }

  override def createOrderReceivers: List[ActorOrderReceiver] = {
    val primaryMatchingEngines = matchingEngines.map(pair => pair._1).toList
    (1 to 10).toList map (i => new ActorOrderReceiver(primaryMatchingEngines))
  }

  override def start {

    for ((p, s) <- matchingEngines) {
      p.start
      // standby is optional
      s.foreach(_.start)
      p.standby = s
    }
    orderReceivers.foreach(_.start)
  }

  override def shutdown {
    orderReceivers.foreach(_ ! "exit")
    for ((p, s) <- matchingEngines) {
      p ! "exit"
      // standby is optional
      s.foreach(_ ! "exit")
    }
  }
}
