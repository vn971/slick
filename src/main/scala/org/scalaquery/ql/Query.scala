package org.scalaquery.ql

import scala.reflect.Manifest
import org.scalaquery.SQueryException
import org.scalaquery.util.{Node, WithOp}

/**
 * A query monad which contains the AST for a query's projection and the accumulated
 * restrictions and other modifiers.
 */
abstract class Query[+E, +U]() extends Node {

  def unpackable: Unpackable[_ <: E, _ <: U]
  lazy val reified = unpackable.reifiedNode
  lazy val linearizer = unpackable.linearizer

  protected[this] def nodeChildGenerators = Seq[Any](reified)
  override def isNamedTable = true

  def flatMap[F, T](f: E => Query[F, T]): Query[F, T] = new Bind[F, T](f(unpackable.value), this)

  def map[F, T](f: E => F)(implicit unpack: Unpack[F, T]): Query[F, T] = flatMap(v => Query(f(v)))

  def >>[F, T](q: Query[F, T]): Query[F, T] = flatMap(_ => q)

  def filter[T, R](f: E => T)(implicit wt: CanBeQueryCondition[T], reify: Reify[E, R]): Query[R, U] =
    new Filter[R, U](this, unpackable.reifiedUnpackable(reify), wt(f(unpackable.value)))

  def withFilter[T, R](f: E => T)(implicit wt: CanBeQueryCondition[T], reify: Reify[E, R]) = filter(f)(wt, reify)

  def where[T <: Column[_], R](f: E => T)(implicit wt: CanBeQueryCondition[T], reify: Reify[E, R]) = filter(f)(wt, reify)

  /*
  def groupBy(by: Column[_]*) =
    new Query[E, U](unpackable, cond, modifiers ::: by.view.map(c => new Grouping(Node(c))).toList)

  def orderBy(by: Ordering*) = new Query[E, U](unpackable, cond, modifiers ::: by.toList)

  def exists = StdFunction[Boolean]("exists", map(_ => ConstColumn(1)))
  */

  def cond: List[Column[_]] = Nil //--
  def modifiers: List[QueryModifier] = Nil //--
  def typedModifiers[T <: QueryModifier]: List[T] = Nil //--

  /*
  // Unpackable queries only
  def union[O >: E, T >: U, R](other: Query[O, T]*)(implicit reify: Reify[O, R]) = wrap(Union(false, this :: other.toList))

  def unionAll[O >: E, T >: U, R](other: Query[O, T]*)(implicit reify: Reify[O, R]) = wrap(Union(true, this :: other.toList))

  def count = ColumnOps.CountAll(Subquery(this, false))

  def sub[UU >: U, R](implicit reify: Reify[E, R]) = wrap(this)

  private def wrap[R](base: Node)(implicit reify: Reify[E, R]): Query[R, U] = {
    def f[EE](unpackable: Unpackable[EE, _ <: U]) = unpackable.endoMap(v => v match {
      case t:AbstractTable[_] =>
        t.mapOp(_ => Subquery(base, false)).asInstanceOf[EE]
      case o =>
        var pos = 0
        val p = Subquery(base, true)
        unpackable.mapOp { v =>
          pos += 1
          SubqueryColumn(pos, p, v match {
            case c: Column[_] => c.typeMapper
            case SubqueryColumn(_, _, tm) => tm
            case _ => throw new SQueryException("Expected Column or SubqueryColumn")
          })
        }
    })
    val r: Unpackable[R, _ <: U] = unpackable.reifiedUnpackable(reify)
    Query[R, U](f(r))
  }

  //def reify[R](implicit reify: Reify[E, R]) =
  //  new Query[R, U](unpackable.reifiedUnpackable, cond, modifiers)

  // Query[Column[_]] only
  def asColumn(implicit ev: E <:< Column[_]): E = unpackable.value.asInstanceOf[WithOp].mapOp(_ => this).asInstanceOf[E]
  */

}

object Query extends PureNoAlias[Unit, Unit](Unpackable((), Unpack.unpackPrimitive[Unit])) {
  def apply[E, U](value: E)(implicit unpack: Unpack[E, U]) = apply[E, U](Unpackable(value, unpack))
  def apply[E, U](unpackable: Unpackable[_ <: E, _ <: U]): Query[E, U] =
    if(unpackable.reifiedNode.isNamedTable) new PureNoAlias[E, U](unpackable)
    else new Pure[E, U](unpackable)
}

trait CanBeQueryCondition[-T] {
  def apply(value: T): Column[_]
}

object CanBeQueryCondition {
  implicit object BooleanColumnCanBeQueryCondition extends CanBeQueryCondition[Column[Boolean]] {
    def apply(value: Column[Boolean]) = value
  }
  implicit object BooleanOptionColumnCanBeQueryCondition extends CanBeQueryCondition[Column[Option[Boolean]]] {
    def apply(value: Column[Option[Boolean]]) = value
  }
  implicit object BooleanCanBeQueryCondition extends CanBeQueryCondition[Boolean] {
    def apply(value: Boolean) = new ConstColumn(value)(TypeMapper.BooleanTypeMapper)
  }
}

case class Subquery(query: Node, rename: Boolean) extends Node {
  protected[this] override def nodeChildGenerators = Seq(query)
  protected[this] override def nodeChildNames = Seq("query")
  override def isNamedTable = true
}

case class SubqueryColumn(pos: Int, subquery: Subquery, typeMapper: TypeMapper[_]) extends Node {
  protected[this] override def nodeChildGenerators = Seq(subquery)
  protected[this] override def nodeChildNames = Seq("subquery")
  override def toString = "SubqueryColumn c"+pos
}

case class Union(all: Boolean, queries: List[Query[_, _]]) extends Node {
  override def toString = if(all) "Union all" else "Union"
  protected[this] def nodeChildGenerators = queries
}

class PureNoAlias[+E, +U](val unpackable: Unpackable[_ <: E, _ <: U]) extends Query[E, U] {
  override def nodeDelegate = reified
  override def isNamedTable = false
}

class Pure[+E, +U](val _unpackable: Unpackable[_ <: E, _ <: U]) extends Query[E, U] {
  val unpackable = _unpackable.endoMap(n => WithOp.mapOp(n, { x => Wrapped(Node(x), Node(this)) }))
  override def toString = "Pure"
  protected[this] override def nodeChildGenerators = Seq(_unpackable.reifiedNode)
  protected[this] override def nodeChildNames = Seq("value")
}

class FilteredQuery[+E, +U](val from: Query[_,_], base: Unpackable[_ <: E, _ <: U]) extends Query[E, U] {
  val unpackable = base.endoMap(n => WithOp.mapOp(n, { x => Wrapped(Node(x), Node(this)) }))
  override def toString = "FilteredQuery:" + getClass.getName.replaceAll(".*\\.", "")
  protected[this] override def nodeChildGenerators = Seq[Any](from)
  protected[this] override def nodeChildNames = Seq("from")
}

class GroupBy[+E, +U](_from: Query[_,_], _base: Unpackable[_ <: E, _ <: U], groupBy: Column[_]) extends FilteredQuery[E, U](_from, _base) {
  protected[this] override def nodeChildGenerators = super.nodeChildGenerators :+ groupBy
  protected[this] override def nodeChildNames = super.nodeChildNames :+ "groupBy"
}

class Filter[+E, +U](_from: Query[_,_], _base: Unpackable[_ <: E, _ <: U], _cond: Column[_]) extends FilteredQuery[E, U](_from, _base) {
  override def cond: List[Column[_]] = List(_cond)
  protected[this] override def nodeChildGenerators = super.nodeChildGenerators ++ cond
  protected[this] override def nodeChildNames = super.nodeChildNames ++ cond.map(_ => "where")
}

class Bind[+E, +U](select: Query[E, U], val from: Query[_,_]) extends Query[E, U] {
  val unpackable = select.unpackable.endoMap(n => WithOp.mapOp(n, { x => Wrapped(Node(x), Node(this)) }))
  override def toString = "Bind"
  protected[this] override def nodeChildGenerators = Seq(from, select)
  protected[this] override def nodeChildNames = Seq("from", "select")
}

case class Wrapped(what: Node, in: Node) extends Node {
  protected[this] def nodeChildGenerators = Seq(what, in)
  protected[this] override def nodeChildNames = Seq("what", "in")
}
