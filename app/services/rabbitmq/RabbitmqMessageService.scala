package services

import java.io.IOException
import java.text.SimpleDateFormat

import akka.actor.{Actor, ActorRef}
import com.ning.http.client.Realm.AuthScheme
import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client.{Channel, DefaultConsumer, Envelope, QueueingConsumer}
import models._
import play.api.Logger
import play.api.Play.current
import play.api.http.MimeTypes
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.ws.{Response, WS}

import scala.concurrent.Future
import scala.util.Try

/**
 * Send/get messages from a message bus
 */

@Singleton
class RabbitmqMessageService extends MessageService {

  /** Open connection to broker. **/
  def connect(): Boolean

  /** Close connection to broker. **/
  def close()

  /** Submit a message to broker. */
  def submit(exchange: String, routing_key: String, message: JsValue) = {}

  def getRestEndPoint(path: String): Future[Response]

  /**
   * Get the exchange list for a given host
   */
  def getExchanges : Future[Response]

  /**
   * get list of queues attached to an exchange
   */
  def getQueuesNamesForAnExchange(exchange: String): Future[Response]

  /**
   * Get the binding lists (lists of routing keys) from the rabbitmq broker
   */
  def getBindings: Future[Response]

  /**
   * Get Channel list from rabbitmq broker
   */
  def getChannelsList: Future[Response]

  /**
   * Get queue details for a given queue
   */
  def getQueueDetails(qname: String): Future[Response]

  /**
   * Get queue bindings for a given host and queue from rabbitmq broker
   */
  def getQueueBindings(qname: String): Future[Response]

  /**
   * Get Channel information from rabbitmq broker for given channel id 'cid'
   */
  def getChannelInfo(cid: String): Future[Response]

  def cancelPendingSubmission(id: UUID, queueName: String, msg_id: UUID)

  /**
   * a helper function to get user email address from user's request api key.
   * @param requestAPIKey user request apikey
   * @return a list of email address
   */
  def getEmailNotificationEmailList(requestAPIKey: Option[String]): List[String]

  /**
   * loop through the queue and dispatch the message via the routing key.
   *
   * @param cancellationQueueConsumer  the queue consumer to download the requests from the cancellation downloaded queue
   * @param channel                    the channel connecting to the rabbitmq
   * @param cancellationSearchTimeout  the timeout of downloading the requests from the rabbitmq
   */
  def resubmitPendingRequests(cancellationQueueConsumer: QueueingConsumer, channel: Channel, cancellationSearchTimeout: Long)

}


/**
 * Send message on specified channel directly to a queue and tells receiver to reply
 * on specified queue.
 */
class PublishDirectActor(channel: Channel, replyQueueName: String) extends Actor {
  val appHttpPort = play.api.Play.configuration.getString("http.port").getOrElse("")
  val appHttpsPort = play.api.Play.configuration.getString("https.port").getOrElse("")
  val clowderurl = play.api.Play.configuration.getString("clowder.rabbitmq.clowderurl")

  def receive = {
    case ExtractorMessage(msgid, fileid, jobid, notifies, intermediateId, host, key, metadata, fileSize, datasetId, flags, secretKey, routingKey,
    source, activity, target) => {
      var theDatasetId = ""
      if (datasetId != null)
        theDatasetId = datasetId.stringify

      val actualHost = clowderurl match {
        case Some(url) => url
        case None => {
          if (!appHttpsPort.equals("")) {
            host.replaceAll("^http:", "https:").replaceFirst(":" + appHttpPort, ":" + appHttpsPort)
          } else {
            host
          }
        }
      }

      val msgMap = scala.collection.mutable.Map(
        "notifies" -> Json.toJson(notifies),
        "msgid" -> Json.toJson(msgid.stringify),
        "id" -> Json.toJson(fileid.stringify),
        "jobid" -> Json.toJson(jobid.get.stringify),
        "intermediateId" -> Json.toJson(intermediateId.stringify),
        "fileSize" -> Json.toJson(fileSize),
        "host" -> Json.toJson(actualHost),
        "datasetId" -> Json.toJson(theDatasetId),
        "flags" -> Json.toJson(flags),
        "secretKey" -> Json.toJson(secretKey),
        "routing_key" -> Json.toJson(routingKey),
        "source" -> Json.toJson(source),
        "activity" -> Json.toJson(activity),
        "target" -> target.map{Json.toJson(_)}.getOrElse(Json.toJson("""{}"""))

      )
      // add extra fields
      // FIXME use Play JSON libraries / JSON Formatter / Readers / Writers
      metadata.foreach(kv =>
        kv._2 match {
          case x: JsValue => msgMap.put(kv._1, x)
          case x: String => msgMap.put(kv._1, Json.toJson(x))
          case _ => msgMap.put(kv._1, Json.toJson(kv._2.toString))
        }

      )
      val msg = Json.toJson(msgMap.toMap)
      // correlation id used for rpc call
      val corrId = java.util.UUID.randomUUID().toString() // TODO switch to models.UUID?
      // setup properties
      val basicProperties = new BasicProperties().builder()
        .contentType(MimeTypes.JSON)
        .correlationId(corrId)
        .replyTo(replyQueueName)
        .deliveryMode(2)
        .build()
      try {
        Logger.debug(s"[$jobid] Sending $msg to $key")
        channel.basicPublish("", key, true, basicProperties, msg.toString().getBytes())
      } catch {
        case e: Exception => {
          Logger.error("Error connecting to rabbitmq broker", e)
          current.plugin[RabbitmqPlugin].foreach {
            _.close()
          }
        }
      }
    }
    case _ => {
      Logger.error("Unknown message type submitted.")
    }
  }
}

/**
 * Listen for responses coming back on replyQueue
 */
class MsgConsumer(channel: Channel, target: ActorRef) extends DefaultConsumer(channel) {
  override def handleDelivery(consumer_tag: String,
                              envelope: Envelope,
                              properties: BasicProperties,
                              body: Array[Byte]) {
    val delivery_tag = envelope.getDeliveryTag
    val body_text = new String(body)

    target ! body_text
    channel.basicAck(delivery_tag, false)
  }
}

/**
 * Actual message on reply queue.
 */
class EventFilter(channel: Channel, queue: String) extends Actor {
  val extractions: ExtractionService = DI.injector.getInstance(classOf[ExtractionService])

  def receive = {
    case statusBody: String =>
      Logger.debug("Received extractor status: " + statusBody)
      val json = Json.parse(statusBody)
      val file_id = UUID((json \ "file_id").as[String])
      val user_id = (json \ "user_id").asOpt[String].fold(User.anonymous.id)(s => UUID(s))
      val job_id: Option[UUID] = (json \ "job_id").asOpt[String] match {
        case Some(jid) => { Some(UUID(jid)) }
        case None => { None }
      }
      val extractor_id = (json \ "extractor_id").as[String]
      val status = (json \ "status").as[String]
      val startDate = (json \ "start").asOpt[String].map(x =>
        Try(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX").parse(x)).getOrElse {
          new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").parse(x)
        })
      val updatedStatus = status.toUpperCase()
      //TODO : Enforce consistent status updates: STARTED, DONE, ERROR and
      //       other detailed status updates to logs when we start implementing
      //       distributed logging
      if (updatedStatus.contains("DONE")) {
        extractions.insert(Extraction(UUID.generate(), file_id, job_id, extractor_id, "DONE", startDate.get, None, user_id))
      } else {
        val commKey = "key=" + play.Play.application().configuration().getString("commKey")
        val parsed_status = status.replace(commKey, "key=secretKey")
        extractions.insert(Extraction(UUID.generate(), file_id, job_id, extractor_id, parsed_status, startDate.get, None, user_id))
      }
      Logger.debug("updatedStatus=" + updatedStatus + " status=" + status + " startDate=" + startDate)
      models.ExtractionInfoSetUp.updateDTSRequests(file_id, extractor_id)
  }
}

/**
 * Listen for heartbeats messages sent by extractors.
 * @param channel
 * @param queue
 */
class ExtractorsHeartbeats(channel: Channel, queue: String) extends Actor {
  val extractions: ExtractionService = DI.injector.getInstance(classOf[ExtractionService])
  val extractorsService: ExtractorService = DI.injector.getInstance(classOf[ExtractorService])

  def receive = {
    case statusBody: String =>
      Logger.debug("Received extractor heartbeat: " + statusBody)
      val json = Json.parse(statusBody)
      // TODO store running extractors ids
      val id = UUID((json \ "id").as[String])
      val queue = (json \ "queue").as[String]
      val extractor_info = json \ "extractor_info"

      // Validate document
      val extractionInfoResult = extractor_info.validate[ExtractorInfo]

      // Update database
      extractionInfoResult.fold(
        errors => {
          Logger.debug("Received extractor heartbeat with bad format: " + extractor_info)
        },
        info => {
          extractorsService.getExtractorInfo(info.name) match {
            case Some(infoFromDB) => {
              // TODO only update if new semantic version is greater than old semantic version
              if (infoFromDB.version != info.version) {
                // TODO keep older versions of extractor info instead of just the latest one
                extractorsService.updateExtractorInfo(info)
                Logger.info("Updated extractor definition for " + info.name)
              }
            }
            case None => {
              extractorsService.updateExtractorInfo(info) match {
                case None => {}
                case Some(eInfo) => {
                  // Create (if needed) and assign default labels
                  eInfo.defaultLabels.foreach(labelStr => {
                    val segments = labelStr.split("/")
                    val (labelName, labelCategory) = if (segments.length > 1) {
                      (segments(1), segments(0))
                    } else {
                      (segments(0), "Other")
                    }
                    extractorsService.getExtractorsLabel(labelName) match {
                      case None => {
                        // Label does not exist - create and assign it
                        val createdLabel = extractorsService.createExtractorsLabel(labelName, Some(labelCategory), List[String](eInfo.name))
                      }
                      case Some(lbl) => {
                        // Label already exists, assign it
                        if (!lbl.extractors.contains(eInfo.name)) {
                          val label = ExtractorsLabel(lbl.id, lbl.name, lbl.category, lbl.extractors ++ List[String](eInfo.name))
                          val updatedLabel = extractorsService.updateExtractorsLabel(label)
                        }
                      }
                    }
                  })
                }
              }

              Logger.info(s"New extractor ${info.name} registered from heartbeat")
            }
          }
        }
      )
  }
}

/** RabbitMQ Bindings retrieve from management API **/
case class Binding(source: String, vhost: String, destination: String, destination_type: String, routing_key: String,
                   arguments: JsObject, properties_key: String)