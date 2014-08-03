package streamz.akka

import akka.actor.{ActorRef, ActorRefFactory, Props}
import akka.stream.actor.ActorConsumer.{RequestStrategy, OnNext, OnComplete, MaxInFlightRequestStrategy}
import akka.stream.actor.ActorProducer
import akka.stream.actor.ActorProducer.{Cancel, Request}
import org.reactivestreams.api.Producer

import scala.collection.immutable.Queue
import scalaz.\/
import scalaz.concurrent.Task
import scalaz.stream._

import scalaz.syntax.id._

package object stream {
  type RequestStrategyFactory = InFlight => RequestStrategy
  
  trait InFlight {
    def inFlight: Int
  }

  def maxInFlightStrategyFactory(max: Int): RequestStrategyFactory = inFlight => new MaxInFlightRequestStrategy(max) {
    override def inFlightInternally = inFlight.inFlight
  } 
  
  def asProducer[O](process: Process[Task, O],
                    strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10),
                    name: Option[String] = None)(
                    implicit actorFactory: ActorRefFactory):
      (Process[Task, Unit], Producer[O]) = {
    val props = AdapterProducer.props[O](strategyFactory)
    val actorRef = name.fold(actorFactory.actorOf(props))(actorFactory.actorOf(props, _))
    (process.to(toSink[O](actorRef)), ActorProducer[O](actorRef))
  }

  private def toSink[I](adapterActor: ActorRef): Sink[Task,I] = {
    io.resource[ActorRef, I => Task[Unit]](
      Task.delay[ActorRef](adapterActor))(
      adapterActor => Task.delay(adapterActor ! OnComplete))( // on error?
      adapterActor => Task.delay[I=>Task[Unit]](i => Task.async[Unit](callback => adapterActor ! OnNext(i, callback))))
  }
}
