import cats._, implicits._
import cats.effect._, concurrent._
import cats.effect.implicits._
import fs2._, io.tcp._
import scala.concurrent.duration._
import scala.util.control.NonFatal

object Examples extends IOApp {
  def run(args: List[String]) = ExitCode.Success.pure[IO]

  implicit class Runner[A](s: Stream[IO, A]) {
    def yolo: Unit = s.compile.drain.unsafeRunSync
    def yoloV: Vector[A] = s.compile.toVector.unsafeRunSync
  }
  // put("hello").to[F]
  def put[A](a: A): IO[Unit] = IO(println(a))

  def yo =
    Stream
      .repeatEval(put("hello"))
      .interruptAfter(2.seconds)
      .yolo

  def address = ???

  def server[F[_]: Concurrent: Timer: ContextShift](
      group: SocketGroup): Stream[F, Unit] =
    group
      .server(address)
      .map { connection =>
        Stream.resource(connection).flatMap { socket =>
          Stream
            .range(0, 10)
            .map(i => s"Ping no $i \n")
            .covary[F]
            .metered(1.second)
            .through(text.utf8Encode)
            .through(socket.writes())
            .onFinalize(socket.endOfOutput)
        }
      }
      .parJoinUnbounded
      .interruptAfter(10.minutes)
}

object ex0 {
  type IO[A] = IO.Token => A
  object IO {
    def apply[A](a: => A): IO[A] = _ => a

    class Token private[IO] ()
    def unsafeRun[A](fa: IO[A]): A = fa(new Token)
  }

  def read = IO(scala.io.StdIn.readLine)
  def put[A](v: A) = IO(println(v))

  def p =
    for {
      _ <- put("insert your name")
      n <- read
      _ <- put(s"Hello $n")
    } yield ()
}

object stack {
  type Stack[A] = List[A]
  implicit class S[A](s: Stack[A]) {
    def push(a: A): Stack[A] = a +: s
    def pop: Option[(A, Stack[A])] = s match {
      case Nil => None
      case x :: xs => (x, xs).some
    }
  }
}

import stack._

// UIO
object ex1 {
  def read = IO(scala.io.StdIn.readLine)
  def put[A](v: A) = IO(println(v))

  def p =
    for {
      _ <- put("insert your name")
      n <- read
      _ <- put(s"Hello $n")
    } yield ()

  sealed trait IO[+A] {
    def r = IO.unsafeRun(this)
  }
  object IO {
    def apply[A](v: => A): IO[A] = Delay(() => v)

    case class FlatMap[B, +A](io: IO[B], k: B => IO[A]) extends IO[A]
    case class Pure[+A](v: A) extends IO[A]
    case class Delay[+A](eff: () => A) extends IO[A]

    implicit def instances: Monad[IO] =
      new Monad[IO] {
        def pure[A](x: A): IO[A] = Pure(x)
        def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B] = FlatMap(fa, f)
        // ignoring stack safety for now
        def tailRecM[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] = ???
      }

    def unsafeRun[A](io: IO[A]): A = {
      def loop(current: IO[Any], stack: Stack[Any => IO[Any]]): A =
        current match {
          case FlatMap(io, k) =>
            loop(io, stack.push(k))
          case Pure(v) =>
            stack.pop match {
              case None => v.asInstanceOf[A]
              case Some((bind, stack)) => loop(bind(v), stack)
            }
          case Delay(body) =>
            val res = body()
            loop(Pure(res), stack)
        }
      loop(io, Nil)
    }
  }
}

// SyncIO
object ex2 {
  def read = IO(scala.io.StdIn.readLine)
  def put[A](v: A) = IO(println(v))
  def prompt = put("What's your name?") >> read
  def hello = prompt.flatMap(n => s"hello $n")

  FlatMap(
    FlatMap(
      Delay(() => print("name?")),
      _ => Delay(() => readLine)
    ),
    n => Delay(() => println(n))
  )


  def p =
    for {
      _ <- put("insert your name")
      n <- read
      _ <- put(s"Hello $n")
    } yield ()
  def p1 = IO[Unit](throw new Exception).handleErrorWith(e => put(e))
  def p2 = IO[Unit](throw new Exception).attempt
  def p3 =
    IO[Unit](throw new Exception)
      .map(_.asRight[Throwable])
      .handleError(_.asLeft[Unit])
  def p4 = IO[Throwable](throw new Exception).flatMap(e => put(e))

  sealed trait IO[+A] {
    def r = IO.unsafeRun(this)
  }
  object IO {
    def apply[A](v: => A): IO[A] = Delay(() => v)

    case class FlatMap[B, +A](io: IO[B], k: B => IO[A]) extends IO[A]
    case class Pure[+A](v: A) extends IO[A]
    case class RaiseError(e: Throwable) extends IO[Nothing]
    case class HandleErrorWith[+A](io: IO[A], k: Throwable => IO[A])
        extends IO[A]
    case class Delay[+A](eff: () => A) extends IO[A]

    implicit def instances: MonadError[IO, Throwable] =
      new MonadError[IO, Throwable] {
        def pure[A](x: A): IO[A] = Pure(x)
        def handleErrorWith[A](fa: IO[A])(f: Throwable => IO[A]): IO[A] =
          HandleErrorWith(fa, f)
        def raiseError[A](e: Throwable): IO[A] = RaiseError(e)
        def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B] =
          FlatMap(fa, f)

        // ignoring stack safety for now
        def tailRecM[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] = ???
      }

    def unsafeRun[A](io: IO[A]): A = {
      sealed trait Bind {
        def isHandler: Boolean = this.isInstanceOf[Bind.H]
      }
      object Bind {
        case class K(f: Any => IO[Any]) extends Bind
        case class H(f: Throwable => IO[Any]) extends Bind
      }

      def loop(current: IO[Any], stack: Stack[Bind]): A =
        current match {
          case FlatMap(io, k) =>
            loop(io, stack.push(Bind.K(k)))
          case Pure(v) =>
            stack.dropWhile(_.isHandler) match {
              case Nil => v.asInstanceOf[A]
              case Bind.K(f) :: stack => loop(f(v), stack)
            }
          case HandleErrorWith(io, h) =>
            loop(io, stack.push(Bind.H(h)))
          case RaiseError(e) =>
            // dropping binds on errors until we find an error handler
            // realises the short circuiting semantics of MonadError
            stack.dropWhile(!_.isHandler) match {
              case Nil => throw e
              case Bind.H(handle) :: stack => loop(handle(e), stack)
            }
          case Delay(body) =>
            try {
              val res = body()
              loop(Pure(res), stack)
            } catch {
              case NonFatal(e) => loop(RaiseError(e), stack)
            }
        }

      loop(io, Nil)
    }
  }

}
