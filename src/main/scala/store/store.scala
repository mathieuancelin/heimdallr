package store

import java.util.concurrent.atomic.AtomicReference

import com.codahale.metrics.MetricRegistry
import models.Service

class Store(initialState: Map[String, Seq[Service]] = Map.empty[String, Seq[Service]], metrics: MetricRegistry) {

  private val readCounter = metrics.counter("store-reads")
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
}
