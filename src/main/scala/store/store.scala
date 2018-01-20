package store

import java.util.concurrent.atomic.AtomicReference

import models.Service

class Store(initialState: Map[String, Service] = Map.empty[String, Service]) {

  private val ref: AtomicReference[Map[String, Service]] = new AtomicReference[Map[String, Service]](initialState)

  def modify(f: Map[String, Service] => Map[String, Service]): Map[String, Service] = {
    ref.updateAndGet(services => f(services))
  }

  def get(): Map[String, Service] = ref.get()
}
