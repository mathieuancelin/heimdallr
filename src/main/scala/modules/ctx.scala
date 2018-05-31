package io.heimdallr.modules

import akka.http.scaladsl.model.HttpRequest
import org.joda.time.DateTime

class TypedKey[A] private (val displayName: Option[String]) {
  def bindValue(value: A): TypedEntry[A] = TypedEntry(this, value)
  def ->(value: A): TypedEntry[A]        = bindValue(value)
  override def toString: String          = displayName.getOrElse(super.toString)
}

case class TypedEntry[A](key: TypedKey[A], value: A) {
  def toPair: (TypedKey[A], A) = (key, value)
}

object TypedKey {
  def apply[A]: TypedKey[A]                      = new TypedKey[A](None)
  def apply[A](displayName: String): TypedKey[A] = new TypedKey[A](Some(displayName))
}

trait TypedMap {
  def apply[A](key: TypedKey[A]): A
  def get[A](key: TypedKey[A]): Option[A]
  def contains(key: TypedKey[_]): Boolean
  def updated[A](key: TypedKey[A], value: A): TypedMap
  def +(entries: TypedEntry[_]*): TypedMap
}

object TypedMap {
  val empty = new DefaultTypedMap(scala.collection.mutable.Map.empty)
  def apply(entries: TypedEntry[_]*): TypedMap = {
    TypedMap.empty.+(entries: _*)
  }
}

class DefaultTypedMap(m: scala.collection.mutable.Map[TypedKey[_], Any]) extends TypedMap {
  override def apply[A](key: TypedKey[A]): A       = m.apply(key).asInstanceOf[A]
  override def get[A](key: TypedKey[A]): Option[A] = m.get(key).asInstanceOf[Option[A]]
  override def contains(key: TypedKey[_]): Boolean = m.contains(key)
  override def updated[A](key: TypedKey[A], value: A): TypedMap = {
    m.updated(key, value)
    this
  }
  override def +(entries: TypedEntry[_]*): TypedMap = {
    entries.foldLeft(m) {
      case (m1, e) => m1.updated(e.key, e.value)
    }
    this
  }
  override def toString: String = m.mkString("{", ", ", "}")
}

case class ReqContext(reqId: String, timestamp: DateTime, args: TypedMap, request: HttpRequest)
