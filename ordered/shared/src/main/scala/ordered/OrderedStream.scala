package ordered

// TODO RETHINK EVERYTHING because SHIT IS BROKEN ugh

// TODO consider making map/flatMap extension methods provided only by monad instances,
// so that when desired you can used the monotone versions monadically (probably by importing?).
// fact is that there are two different "monads" and they're not even monads in the traditional sense;
// they're both monads on slightly different categories, I think...
// TODO figure out exactly what those are. One (the stricter "monotone" one)
// definitely is just partially ordered types.
// but for the other, a structure-preserving map can destroy the equalities in a partial order.
// hummm...
// TODO trampoline this as well. Def gonna get stack overflows once things get big

/* A cleaner implementation of sorted streams.
 * Note that this implementation is strict in its head.
 * So if you ever dropWhile or filter out all the elements, you'll immediately go into an infinite loop.
 *
 * Also I realized there are really two kinds of monotone functions we're dealing with here;
 * they may respect the strict ordering (while possibly changing the order between "equal" things)
 * or (more strongly) the non-strict ordering, ensuring that equal things remain equal.
 * The second may be implemented more efficiently, not looking at any of the children at all,
 * whereas the first always has to look at at least one child to make sure the head is still the smallest.
 */
sealed abstract class OrderedStream[A](implicit val order: Ordering[A]) {

  import OrderedStream._

  def headOption: Option[A]
  def tailOption: Option[OrderedStream[A]]
  def isEmpty: Boolean
  def ifNonEmpty: Option[:<[A]]

  def find(p: A => Boolean): Option[A]

  def insert(a: A): OrderedStream[A]

  // f must respect the strict version of the preorder, as in: a < b => f(a) < f(b).
  def map[B : Ordering](f: A => B): OrderedStream[B]
  // STRONGER requirement: f must respect the non-strict version of the preorder, as in: a <= b => f(a) <= f(b)
  // more efficient because we don't need to examine the last element.
  def mapMonotone[B : Ordering](f: A => B): OrderedStream[B]

  // monadic join
  def flatten[B : Ordering](implicit ev: A =:= OrderedStream[B], ev2: OrderedStream[B] =:= A): OrderedStream[B]

  // f again must be monotonic (strict)
  def flatMap[B : Ordering](f: A => OrderedStream[B]): OrderedStream[B] =
    map(f).flatten

  // f again must be monotonic (non-strict)
  def flatMapMonotone[B : Ordering](f: A => OrderedStream[B]): OrderedStream[B] =
    mapMonotone(f).flatten

  // be careful about filtering all of the elements from an infinite stream---that will cause nontermination
  def filter(p: A => Boolean): OrderedStream[A]
  // this is already lazy, not worth extra complexity to try to do any better.
  @inline final def withFilter(p: A => Boolean): OrderedStream[A] = filter(p)
  @inline final def filterNot(p: A => Boolean): OrderedStream[A] = filter(a => !p(a))

  def merge(other: OrderedStream[A]): OrderedStream[A] = other

  def take(n: Int): OrderedStream[A]

  def takeWhile(f: A => Boolean): OrderedStream[A]

  def drop(n: Int): OrderedStream[A]

  def dropWhile(f: A => Boolean): OrderedStream[A]

  def removeFirst(p: A => Boolean): OrderedStream[A]

  def collect[B : Ordering](f: PartialFunction[A, B]): OrderedStream[B]
  def collectMonotone[B : Ordering](f: PartialFunction[A, B]): OrderedStream[B]

  // careful to take(n) first! this won't terminate if you're infinite
  def toList: List[A]

  def toStream: Stream[A]

  // utility methods for internal use

  // all elements of other guaranteed to be >= all elements of this
  protected[ordered] def append(other: => OrderedStream[A]): OrderedStream[A]
}

object OrderedStream extends OrderedStreamInstances {

  def empty[A : Ordering]: OrderedStream[A] =
    ONil[A]

  def unit[A : Ordering](el: A): OrderedStream[A] =
    el :< ONil[A]

  def unfold[B, A : Ordering](unacc: B, uncons: B => Option[(A, B)]): OrderedStream[A] = uncons(unacc) match {
    case None => empty[A]
    case Some((head, remainder)) => head :< unfold(remainder, uncons)
  }

  // requirement: for all a: A, a <= s(a)
  def recurrence[A : Ordering](z: A, s: A => A): :<[A] =
    z :< recurrence(s(z), s)

  // for use with side-effecting computations. assumes compute <= subsequent computes.
  // repeats the computation until no result is returned.
  def exhaustively[A : Ordering](compute: => Option[A]): OrderedStream[A] = compute match {
    case None => empty[A]
    case Some(a) => a :< exhaustively(compute)
  }

  def fromIndexedSeq[A : Ordering](is: IndexedSeq[A]): OrderedStream[A] =
    is.sorted.foldRight(empty[A])(_ :< _)

  // TODO lazy quicksort is cool, but only useful once we have stack safety, honestly...
  // def fromIndexedSeq[A](is: IndexedSeq[A])(implicit ord: Ordering[A]): OrderedStream[A] = {
  //   if(is.size < 7) {
  //     is.sorted.foldRight(empty[A])(_ :< _)
  //   } else {
  //     // median of 3 randomly chosen pivots
  //     val pivot = {
  //       import util.{Random => r}
  //       val piv1 = is(r.nextInt(is.size))
  //       val piv2 = is(r.nextInt(is.size))
  //       val piv3 = is(r.nextInt(is.size))
  //       if(ord.lteq(piv1, piv2)) ord.max(piv1, piv3) else ord.max(piv2, piv3)
  //     }
  //     val left = is.filter(ord.lt(_, pivot))
  //     OrderedStream.fromIndexedSeq(left).append(
  //       is.filter(ord.equiv(_, pivot)).foldRight(empty[A])(_ :< _) // all eq to pivot are already sorted
  //         .append(OrderedStream.fromIndexedSeq(is.filter(ord.gt(_, pivot))))
  //     )
  //   }
  // }

  protected[ordered] def fromSortedSeq[A : Ordering](s: Seq[A]): OrderedStream[A] = {
    s.foldRight(empty[A])(_ :< _)
  }

  def fromSeq[A : Ordering](is: Seq[A]): OrderedStream[A] =
    fromIndexedSeq(is.toIndexedSeq)

  def fromIterator[A : Ordering](is: Iterator[A]): OrderedStream[A] =
    fromIndexedSeq(is.toVector)

  def fromOption[A : Ordering](opt: Option[A]): OrderedStream[A] = opt match {
    case None => empty[A]
    case Some(a) => unit(a)
  }
}

import OrderedStream._

class ONil[A](implicit order: Ordering[A]) extends OrderedStream[A]()(order) {
  override def headOption = None
  override def tailOption = None
  override def isEmpty = true
  override def ifNonEmpty = None

  override def find(p: A => Boolean) =
    None
  override def insert(a: A): :<[A] =
    a :< this
  override def map[B : Ordering](f: A => B) =
    ONil[B]
  override def mapMonotone[B : Ordering](f: A => B) =
    ONil[B]
  override def flatten[B : Ordering](implicit ev: A =:= OrderedStream[B], ev2: OrderedStream[B] =:= A) =
    ONil[B]
  override def filter(p: A => Boolean) =
    this
  override def merge(other: OrderedStream[A]) =
    other
  override def take(n: Int) =
    this
  override def takeWhile(f: A => Boolean) =
    this
  override def drop(n: Int) =
    this
  override def dropWhile(f: A => Boolean) =
    this
  override def removeFirst(p: A => Boolean) =
    this
  override def collect[B : Ordering](f: PartialFunction[A, B]) =
    ONil[B]
  override def collectMonotone[B : Ordering](f: PartialFunction[A, B]) =
    ONil[B]

  override def toList = Nil
  override def toStream = Stream.empty[A]

  override def toString = s"ONil"

  override protected[ordered] def append(other: => OrderedStream[A]): OrderedStream[A] =
    other
}

object ONil {
  def apply[A : Ordering] = new ONil[A]
  def unapply(sc: ONil[_]): Boolean = true
}

// assumes head is lower order than everything in tail
class :<[A] protected[ordered] (
  val head: A,
  _tail: => OrderedStream[A])(implicit order: Ordering[A]) extends OrderedStream[A]()(order) {
  lazy val tail = _tail

  override def headOption = Some(head)
  override def tailOption = Some(tail)
  override def isEmpty = false
  override def ifNonEmpty = Some(this)

  override def find(p: A => Boolean) = if(p(head)) {
    Some(head)
  } else {
    tail.find(p)
  }

  override def insert(a: A): :<[A] = {
    if(order.lteq(a, head)) a :< this
    else head :< tail.insert(a)
  }

  override def map[B : Ordering](f: A => B): :<[B] = tail match {
    case ONil() => f(head) :< ONil[B]
    case t @ :<(second, _) =>
      if(order.lt(head, second)) f(head) :< t.map(f)
      else t.map(f).insert(f(head))
  }
  override def mapMonotone[B : Ordering](f: A => B): :<[B] =
    f(head) :< tail.mapMonotone(f)

  override def flatten[B : Ordering](implicit ev: A =:= OrderedStream[B], ev2: OrderedStream[B] =:= A) = ev(head) match {
    case ONil() => tail.flatten[B]
    case h :<+ t => h :< tail.insert(t).flatten[B]
  }

  override def filter(p: A => Boolean) = if(p(head)) {
    head :< tail.filter(p)
  } else {
    tail.filter(p)
  }

  override def merge(other: OrderedStream[A]) = other match {
    case ONil() => this
    case h :< t => if(order.lteq(head, h)) {
      head :< tail.merge(other)
    } else {
      h :< t().merge(this)
    }
  }

  override def take(n: Int) = if(n <= 0) {
    empty[A]
  } else {
    head :< tail.take(n - 1)
  }

  override def takeWhile(p: A => Boolean) = if(p(head)) {
    head :< tail.takeWhile(p)
  } else {
    empty[A]
  }

  override def drop(n: Int) = tail.drop(n - 1)

  override def dropWhile(p: A => Boolean) = if(p(head)) {
    tail.dropWhile(p)
  } else {
    this
  }

  override def removeFirst(p: A => Boolean) = if(p(head)) {
    tail
  } else {
    head :< tail.removeFirst(p)
  }

  // TODO test no stack overflow or whatever
  override def collect[B : Ordering](f: PartialFunction[A, B]) =
    f.lift(head).fold(tail.collect(f))(tail.collect(f).insert)
  override def collectMonotone[B : Ordering](f: PartialFunction[A, B]) =
    f.lift(head).fold(tail.collect(f))(_ :< tail.collect(f))

  override def toList: List[A] = head :: tail.toList
  override def toStream: Stream[A] = head #:: tail.toStream

  override def toString = s"$head :< ?"

  // protected util methods

  override protected[ordered] def append(other: => OrderedStream[A]): OrderedStream[A] =
    head :< tail.append(other)
}

// don't evaluate the tail
object :< {
  def unapply[A](sc: :<[A]): Option[(A, () => OrderedStream[A])] = Some((sc.head, () => sc.tail))
}
// evaluate the tail
object :<+ {
  def unapply[A](sc: :<[A]): Option[(A, OrderedStream[A])] = Some((sc.head, sc.tail))
}

trait OrderedStreamInstances {
  implicit def streamOrdering[A](implicit ord: Ordering[A]): Ordering[OrderedStream[A]] = new Ordering[OrderedStream[A]] {
    def compare(a: OrderedStream[A], b: OrderedStream[A]): Int = (a.headOption, b.headOption) match {
      case (None, None) => 0
      case (None, _) => -1
      case (_, None) => 1
      case (Some(x), Some(y)) => ord.compare(x, y)
    }
  }
  implicit def consOrdering[A](implicit ord: Ordering[A]): Ordering[:<[A]] = new Ordering[:<[A]] {
    def compare(a: :<[A], b: :<[A]): Int = ord.compare(a.head, b.head)
  }
}

object OrderedStreamExamples {
  def intsFrom(x: Int) = OrderedStream.recurrence(x, ((y: Int) => y + 1))
}
