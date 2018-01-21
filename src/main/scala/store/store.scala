package store

import java.util.concurrent.atomic.AtomicReference

import com.codahale.metrics.MetricRegistry
import models.Service
import util.{Startable, Stoppable}

class Store(initialState: Map[String, Seq[Service]] = Map.empty[String, Seq[Service]], metrics: MetricRegistry)
    extends Startable[Store]
    with Stoppable[Store] {

  private val readCounter  = metrics.counter("store-reads")
  private val writeCounter = metrics.counter("store-writes")

  private val ref: AtomicReference[Map[String, Seq[Service]]] =
    new AtomicReference[Map[String, Seq[Service]]](initialState)

  def modify(f: Map[String, Seq[Service]] => Map[String, Seq[Service]]): Map[String, Seq[Service]] = {
    readCounter.inc()
    ref.updateAndGet(services => f(services))
  }

  def get(): Map[String, Seq[Service]] = {
    writeCounter.inc()
    ref.get()
  }

  override def start(): Stoppable[Store] = this

  override def stop(): Unit = ()
}
