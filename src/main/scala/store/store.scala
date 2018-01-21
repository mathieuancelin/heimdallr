package store

import java.util.concurrent.atomic.AtomicReference

import models.Service

class Store(initialState: Map[String, Seq[Service]] = Map.empty[String, Seq[Service]]) {

  private val ref: AtomicReference[Map[String, Seq[Service]]] =
    new AtomicReference[Map[String, Seq[Service]]](initialState)

  def modify(f: Map[String, Seq[Service]] => Map[String, Seq[Service]]): Map[String, Seq[Service]] = {
    ref.updateAndGet(services => f(services))
  }

  def get(): Map[String, Seq[Service]] = ref.get()
}
