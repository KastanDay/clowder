package models

import play.api.libs.json.{Json, JsValue, Writes}
import securesocial.core.Identity
import java.util.Date

/**
  * Created by todd_n on 1/11/16.
  */
case class Template (
  id : UUID = UUID.generate,
  author : Identity,
  created : Date,
  name : String,
  lastModified : Date = new Date(),
  keys : List[String] = List.empty)

  object Template{
    implicit val templateWrites = new Writes[Template]{
      def writes(template : Template): JsValue ={
        val templateAuthor = template.author.identityId.userId
        Json.obj("id" -> template.id.toString, "author" -> templateAuthor, "keys"-> template.keys.toList)
      }
    }
}
