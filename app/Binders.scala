import java.util.{UUID => JavaUUID}

import models.UUID
import play.api.mvc._
import org.bson.types.ObjectId
import play.api.mvc.JavascriptLiteral

object Binders {

  type ObjectId = org.bson.types.ObjectId

  /**
   * QueryString binder for ObjectId
   */
  implicit def objectIdQueryStringBindable = new QueryStringBindable[ObjectId] {
    def bind(key: String, params: Map[String, Seq[String]]) = params.get(key).flatMap(_.headOption).map { value =>
      if (ObjectId.isValid(value))
        Right(new ObjectId(value))
      else
        Left("Cannot parse parameter " + key + " as ObjectId")
    }
    def unbind(key: String, value: ObjectId) = key + "=" + value.toString
  }

  /**
   * Path binder for ObjectId.
   */
  implicit def objectIdPathBindable = new PathBindable[ObjectId] {
    def bind(key: String, value: String) = {
      if (ObjectId.isValid(value))
        Right(new ObjectId(value))
      else
        Left("Cannot parse parameter " + key + " as ObjectId")
    }
    def unbind(key: String, value: ObjectId) = value.toString
  }

  /**
   * Convert a ObjectId to a Javascript String
   */
  implicit def objectIdJavascriptLitteral = new JavascriptLiteral[ObjectId] {
    def to(value: ObjectId) = value.toString
  }

  /**
   * UUID path binder
   */
  implicit def uuidPathBinder(implicit stringBinder: PathBindable[String]) = new PathBindable[UUID] {
    override def bind(key: String, value: String): Either[String, UUID] = {
      if (UUID.isValid(value))
        Right(UUID(value))
      else
        Left(s"Cannot parse parameter $key")
    }
    override def unbind(key: String, uuid: UUID): String = uuid.stringify
  }

  /**
   * UUID query binder
   */
  implicit def uuidQueryBinder(implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[UUID] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, UUID]] = {
      val value = params(key)(0)
      if (UUID.isValid(value))
        Some(Right(UUID(value)))
      else
        Some(Left(s"Cannot parse parameter $key"))
    }
    override def unbind(key: String, uuid: UUID): String = uuid.stringify
  }

  /**
   * A `java.util.UUID` bindable.
   */
  implicit object UUIDPathBindable extends PathBindable[JavaUUID] {
    def bind(key: String, value: String) = try {
      Right(JavaUUID.fromString(value))
    } catch {
      case _: Exception => Left("Cannot parse parameter '" + key + "' with value '" + value + "' as UUID")
    }

    def unbind(key: String, value: JavaUUID): String = value.toString
  }
}
