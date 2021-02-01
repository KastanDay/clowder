package api

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.{Deflater, ZipOutputStream}
import java.util.{Calendar, Date}

import javax.inject.{Inject, Singleton}

import scala.collection.immutable.List
import scala.collection.mutable.{HashMap, ListBuffer}
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import play.api.libs.json.Json.toJson
import play.api.libs.json.{JsObject, JsValue, _}
import play.api.{Configuration, Logger}
import akka.stream.scaladsl.Source
import Iterators.CollectionIterator
import akka.NotUsed
import api.Permission.Permission
import controllers.Utils
import services._
import models.{services, _}



/**
 * Manipulate collections.
 */
@Singleton
class Collections @Inject() (datasets: DatasetService,
                             collections: CollectionService,
                             previews: PreviewService,
                             userService: UserService,
                             events: EventService,
                             spaces:SpaceService,
                             appConfig: AppConfigurationService,
                             adminsNotifierService: AdminsNotifierService,
                             configuration: Configuration) extends ApiController {

  def createCollection() = PermissionAction(Permission.CreateCollection) (parse.json) { implicit request =>
    Logger.debug("Creating new collection")
    (request.body \ "name").asOpt[String].map { name =>

      var c : Collection = null
      implicit val user = Some(request.identity)

      val description = (request.body \ "description").asOpt[String].getOrElse("")
      (request.body \ "space").asOpt[String] match {
        case None | Some("default") => c = Collection(name = name, description = description, created = new Date(), datasetCount = 0, author = request.identity, stats = new Statistics())
        case Some(space) =>  if (spaces.get(UUID(space)).isDefined) {

          c = Collection(name = name, description = description, created = new Date(), datasetCount = 0, author = request.identity, spaces = List(UUID(space)), stats = new Statistics())
        } else {
          BadRequest(toJson("Bad space = " + space))
        }
      }

      collections.insert(c) match {
        case Some(id) => {
          appConfig.incrementCount('collections, 1)
          spaces.get(c.spaces).found.foreach(s => {
            spaces.addCollection(c.id, s.id, user)
            collections.addToRootSpaces(c.id, s.id)
            events.addSourceEvent(user, c.id, c.name, s.id, s.name, EventType.ADD_COLLECTION_SPACE.toString)
          })
          Ok(toJson(Map("id" -> id)))
        }
        case None => Ok(toJson(Map("status" -> "error")))
      }
    }.getOrElse(BadRequest(toJson("Missing parameter [name]")))
  }

  def attachDataset(collectionId: UUID, datasetId: UUID) = PermissionAction(Permission.AddResourceToCollection, Some(ResourceRef(ResourceRef.collection, collectionId))) { implicit request =>
    collections.addDataset(collectionId, datasetId) match {
      case Success(_) => {
        val datasetsInCollection: Int = collections.get(collectionId) match {
          case Some(collection) => {
            datasets.get(datasetId) match {
              case Some(dataset) => {
                if (configuration.get[Boolean]("addDatasetToCollectionSpace")) {
                  collections.addDatasetToCollectionSpaces(collection, dataset, Some(request.identity))
                }
                events.addSourceEvent(Some(request.identity), dataset.id, dataset.name, collection.id, collection.name, EventType.ATTACH_DATASET_COLLECTION.toString)
              }
              case None =>
            }
            collection.datasetCount
          }
          case None => 0
        }
        //datasetsInCollection is the number of datasets in this collection
        Ok(Json.obj("datasetsInCollection" -> Json.toJson(datasetsInCollection)))
      }
      case Failure(t) => InternalServerError
    }

  }

  /**
   * Reindex the given collection, if recursive is set to true it will
   * also reindex all datasets and files.
   */
  def reindex(id: UUID, recursive: Boolean) = PermissionAction(Permission.CreateCollection, Some(ResourceRef(ResourceRef.collection, id))) {  implicit request =>
      collections.get(id) match {
        case Some(coll) => {
          collections.index(id)
          Ok(toJson(s"Collection $id reindexed"))
        }
        case None => {
          Logger.error("Error getting collection" + id)
          BadRequest(toJson(s"The given collection id $id is not a valid ObjectId."))
        }
      }
  }

  def removeDataset(collectionId: UUID, datasetId: UUID, ignoreNotFound: String) = PermissionAction(Permission.RemoveResourceFromCollection, Some(ResourceRef(ResourceRef.collection, collectionId)), Some(ResourceRef(ResourceRef.dataset, datasetId))) { implicit request =>
    collections.removeDataset(collectionId, datasetId, Try(ignoreNotFound.toBoolean).getOrElse(true)) match {
      case Success(_) => {
        collections.get(collectionId) match {
        case Some(collection) => {
          datasets.get(datasetId) match {
            case Some(dataset) => {
              events.addSourceEvent(Some(request.identity), dataset.id, dataset.name, collection.id, collection.name, EventType.REMOVE_DATASET_COLLECTION.toString)
            }
            case None =>
          }
        }
        case None =>
      }
      Ok(toJson(Map("status" -> "success")))
    }
    case Failure(t) => {
      Logger.error("Error: " + t)
      InternalServerError
    }
    }
  }

  def removeCollection(collectionId: UUID) = PermissionAction(Permission.DeleteCollection, Some(ResourceRef(ResourceRef.collection, collectionId))) { implicit request =>
    collections.get(collectionId) match {
      case Some(collection) => {
        val useTrash = configuration.get[Boolean]("useTrash")
        if (!useTrash || (useTrash && collection.trash)){
          events.addObjectEvent(Some(request.identity) , collection.id, collection.name, EventType.DELETE_COLLECTION.toString)
          collections.delete(collectionId)
          adminsNotifierService.sendAdminsNotification(Utils.baseUrl(request).toString,"Collection","removed",collection.id.stringify, collection.name)
        } else {
          collections.addToTrash(collectionId, Some(new Date()))
          events.addObjectEvent(Some(request.identity), collectionId, collection.name, "move_collection_trash")
          Ok(toJson(Map("status" -> "success")))
        }
      }
    }
    //Success anyway, as if collection is not found it is most probably deleted already
    Ok(toJson(Map("status" -> "success")))
  }

  def restoreCollection(collectionId : UUID) = PermissionAction(Permission.DeleteCollection, Some(ResourceRef(ResourceRef.collection, collectionId))) {implicit request=>
    implicit val user = Some(request.identity)
    collections.get(collectionId) match {
      case Some(col) => {
        collections.restoreFromTrash(collectionId, None)
        events.addObjectEvent(user, collectionId, col.name, "restore_collection_trash")
        Ok(toJson(Map("status" -> "success")))
      }
      case None => InternalServerError("Update Access failed")
    }
  }

  def listCollectionsInTrash(limit : Int) = PrivateServerAction {implicit request =>
    val trash_collections_list = request.user match {
      case Some(usr) => collections.listUserTrash(request.user,limit).map(jsonCollection(_))
      case None => List.empty
    }
    Ok(toJson(trash_collections_list))
  }

  def emptyTrash() = PrivateServerAction {implicit request =>
    val user = request.user
    user match {
      case Some(u) => {
        collections.listUserTrash(request.user,0).foreach(collection => {
          events.addObjectEvent(request.user , collection.id, collection.name, EventType.DELETE_COLLECTION.toString)
          collections.delete(collection.id)
          adminsNotifierService.sendAdminsNotification(Utils.baseUrl(request),"Collection","removed",collection.id.stringify, collection.name)
        })
      }
      case None =>
    }
    Ok(toJson("Done emptying trash"))
  }

  def clearOldCollectionsTrash(days : Int) = ServerAdminAction {implicit request =>

    val deleteBeforeCalendar : Calendar = Calendar.getInstance()
    deleteBeforeCalendar.add(Calendar.DATE,-days)

    val deleteBeforeDateTime = deleteBeforeCalendar.getTimeInMillis()
    val user = request.user
    user match {
      case Some(u) => {
        collections.listUserTrash(None,0).foreach( c => {
          val dateInTrash = c.dateMovedToTrash.getOrElse(new Date())
          if (dateInTrash.getTime() < deleteBeforeDateTime) {
            events.addObjectEvent(request.user , c.id, c.name, EventType.DELETE_COLLECTION.toString)
            collections.delete(c.id)
            adminsNotifierService.sendAdminsNotification(Utils.baseUrl(request),"Collection","removed",c.id.stringify, c.name)
          }
        })
        Ok(toJson("Deleted all collections in trash older than " + days + " days"))
      }
      case None => BadRequest("No user supplied")
    }
  }

  def list(title: Option[String], date: Option[String], limit: Int, exact: Boolean) = PrivateServerAction { implicit request =>
    Ok(toJson(listCollections(title, date, limit, Set[Permission](Permission.ViewCollection), false, request.user, request.user.fold(false)(_.superAdminMode), exact)))
  }

  def listCanEdit(title: Option[String], date: Option[String], limit: Int, exact: Boolean) = PrivateServerAction { implicit request =>
    Ok(toJson(listCollections(title, date, limit, Set[Permission](Permission.AddResourceToCollection, Permission.EditCollection), false, request.user, request.user.fold(false)(_.superAdminMode), exact)))
  }

  def addDatasetToCollectionOptions(datasetId: UUID, title: Option[String], date: Option[String], limit: Int, exact: Boolean) = PrivateServerAction { implicit request =>
    implicit val user = request.user
    var listAll = false
    var collectionList: List[Collection] = List.empty
    if(configuration.get[Boolean]("enable_sharing")) {
      listAll = true
    } else {
      datasets.get(datasetId) match {
        case Some(dataset) => {
          if(dataset.spaces.length > 0) {
            collectionList = collections.listInSpaceList(title, date, limit, dataset.spaces, Set[Permission](Permission.AddResourceToCollection, Permission.EditCollection), user)
          } else {
            listAll = true
          }

        }
        case None => Logger.debug("The dataset was not found")
      }
    }
    if(listAll) {
      collectionList = listCollections(title, date, limit, Set[Permission](Permission.AddResourceToCollection, Permission.EditCollection), false, request.user, request.user.fold(false)(_.superAdminMode), exact)
    }
    Ok(toJson(collectionList))
  }

  private def getNextLevelCollections(current_collections: List[Collection]): List[Collection] = {
    collections.get(current_collections.map(_.child_collection_ids).flatten).found
  }

  def listPossibleParents(currentCollectionId : String, title: Option[String], date: Option[String], limit: Int, exact: Boolean) = PrivateServerAction { implicit request =>
    val selfAndAncestors = collections.getSelfAndAncestors(UUID(currentCollectionId))
    val descendants = collections.getAllDescendants(UUID(currentCollectionId)).toList
    val allCollections = listCollections(title, date, limit, Set[Permission](Permission.AddResourceToCollection, Permission.EditCollection), false,
      request.user, request.user.fold(false)(_.superAdminMode), exact)
    val possibleNewParents = allCollections.filter(c =>
      if(configuration.get[Boolean]("enable_sharing")) {
        (!selfAndAncestors.contains(c) && !descendants.contains(c))
      } else {
        collections.get(UUID(currentCollectionId)) match {
          case Some(coll) => {
            if(coll.spaces.length == 0) {
               (!selfAndAncestors.contains(c) && !descendants.contains(c))

            } else {
               (!selfAndAncestors.contains(c) && !descendants.contains(c) && c.spaces.intersect(coll.spaces).length > 0)
            }
          }
          case None => (!selfAndAncestors.contains(c) && !descendants.contains(c))
        }
      }
    )
    Ok(toJson(possibleNewParents))
  }

  /**
   * Returns list of collections based on parameters and permissions.
   * TODO this needs to be cleaned up when do permissions for adding to a resource
   */
  private def listCollections(title: Option[String], date: Option[String], limit: Int, permission: Set[Permission], mine: Boolean, user: Option[User], superAdmin: Boolean, exact: Boolean) : List[Collection] = {
    if (mine && user.isEmpty) return List.empty[Collection]

    (title, date) match {
      case (Some(t), Some(d)) => {
        if (mine)
          collections.listUser(d, true, limit, t, user, superAdmin, user.get, exact)
        else
          collections.listAccess(d, true, limit, t, permission, user, superAdmin, true,false, exact)
      }
      case (Some(t), None) => {
        if (mine)
          collections.listUser(limit, t, user, superAdmin, user.get, exact)
        else
          collections.listAccess(limit, t, permission, user, superAdmin, true,false, exact)
      }
      case (None, Some(d)) => {
        if (mine)
          collections.listUser(d, true, limit, user, superAdmin, user.get)
        else
          collections.listAccess(d, true, limit, permission, user, superAdmin, true,false)
      }
       case (None, None) => {
        if (mine)
          collections.listUser(limit, user, superAdmin, user.get)
        else
          collections.listAccess(limit, permission, user, superAdmin, true,false)
      }
    }
  }

  def getCollection(collectionId: UUID) = PermissionAction(Permission.ViewCollection, Some(ResourceRef(ResourceRef.collection, collectionId))) { implicit request =>
    collections.get(collectionId) match {
      case Some(x) => Ok(jsonCollection(x))
      case None => BadRequest(toJson("collection not found"))
    }
  }

  // TODO: should use toJson(Collection) to use implicit writes in Collection model
  def jsonCollection(collection: Collection): JsValue = {
    toJson(Map(
      "id" -> collection.id.toString,
      "name" -> collection.name,
      "description" -> collection.description,
      "created" -> collection.created.toString,
      "author"-> collection.author.email.toString,
      "root_flag" -> collections.hasRoot(collection).toString,
      "child_collection_ids"-> collection.child_collection_ids.toString,
      "parent_collection_ids" -> collection.parent_collection_ids.toString,
      "childCollectionsCount" -> collection.childCollectionsCount.toString,
      "datasetCount"-> collection.datasetCount.toString,
      "spaces" -> collection.spaces.toString)
    )
  }

  def updateCollectionName(id: UUID) = PermissionAction(Permission.EditCollection, Some(ResourceRef(ResourceRef.collection, id)))(parse.json) {
    implicit request =>
      implicit val user = Some(request.identity)
      if (UUID.isValid(id.stringify)) {
        var name: String = null
        val aResult = (request.body \ "name").validate[String]
        aResult match {
          case s: JsSuccess[String] => {
            name = s.get
          }
          case e: JsError => {
            Logger.error("Errors: " + JsError.toJson(e).toString())
            BadRequest(toJson(s"name data is missing"))
          }
        }
        Logger.debug(s"Update title for collection with id $id. New name: $name")
        collections.updateName(id, name)
        events.addObjectEvent(user, id, name, "update_collection_information")
        collections.index(id)
        Ok(Json.obj("status" -> "success"))
      }
      else {
        Logger.error(s"The given id $id is not a valid ObjectId.")
        BadRequest(toJson(s"The given id $id is not a valid ObjectId."))
      }
  }

  def updateCollectionDescription(id: UUID) = PermissionAction(Permission.EditCollection, Some(ResourceRef(ResourceRef.collection, id)))(parse.json) {
    implicit request =>
      implicit val user = Some(request.identity)
      if (UUID.isValid(id.stringify)) {
        var description: String = null
        val aResult = (request.body \ "description").validate[String]
        aResult match {
          case s: JsSuccess[String] => {
            description = s.get
          }
          case e: JsError => {
            Logger.error("Errors: " + JsError.toJson(e).toString())
            BadRequest(toJson(s"description data is missing"))
          }
        }
        Logger.debug(s"Update description for collection with id $id. New description: $description")
        collections.updateDescription(id, description)
        collections.get(id) match {
          case Some(collection) => {
            events.addObjectEvent(user, id, collection.name, "update_collection_information")
          }
          case None => {}
        }
        collections.index(id)
        Ok(Json.obj("status" -> "success"))
      }
      else {
        Logger.error(s"The given id $id is not a valid ObjectId.")
        BadRequest(toJson(s"The given id $id is not a valid ObjectId."))
      }

  }
  /**
   * Add preview to file.
   */
  def attachPreview(collection_id: UUID, preview_id: UUID) = PermissionAction(Permission.EditCollection, Some(ResourceRef(ResourceRef.collection, collection_id)))(parse.json) { implicit request =>
      // Use the "extractor_id" field contained in the POST data.  Use "Other" if absent.
      val eid = (request.body \ "extractor_id").asOpt[String]
      val extractor_id = if (eid.isDefined) {
        eid
      } else {
        Logger.debug("api.Files.attachPreview(): No \"extractor_id\" specified in request, set it to None.  request.body: " + request.body.toString)
        Some("Other")
      }
      val preview_type = (request.body \ "preview_type").asOpt[String].getOrElse("")
      request.body match {
        case JsObject(fields) => {
          collections.get(collection_id) match {
            case Some(collection) => {
              previews.get(preview_id) match {
                case Some(preview) =>
                  // "extractor_id" is stored at the top level of "Preview".  Remove it from the "metadata" field to avoid dup.
                  // TODO replace null with None
                  previews.attachToCollection(preview_id, collection_id, preview_type, extractor_id, request.body)
                  Ok(toJson(Map("status" -> "success")))
                case None => BadRequest(toJson("Preview not found"))
              }
            }
            //If file to be previewed is not found, just delete the preview
            case None => {
              previews.get(preview_id) match {
                case Some(preview) =>
                  Logger.debug("Collection not found. Deleting previews.files " + preview_id)
                  previews.removePreview(preview)
                  BadRequest(toJson("Collection not found. Preview deleted."))
                case None => BadRequest(toJson("Preview not found"))
              }
            }
          }
        }
        case _ => Ok("received something else: " + request.body + '\n')
      }
  }

  def follow(id: UUID) = AuthenticatedAction { implicit request =>
      implicit val user = request.user

      user match {
        case Some(loggedInUser) => {
          collections.get(id) match {
            case Some(collection) => {
              events.addObjectEvent(user, id, collection.name, EventType.FOLLOW_COLLECTION.toString)
              collections.addFollower(id, loggedInUser.id)
              userService.followCollection(loggedInUser.id, id)

              val recommendations = getTopRecommendations(id, loggedInUser)
              recommendations match {
                case x::xs => Ok(Json.obj("status" -> "success", "recommendations" -> recommendations))
                case Nil => Ok(Json.obj("status" -> "success"))
              }
            }
            case None => {
              NotFound
            }
          }
        }
        case None => {
          Unauthorized
        }
      }
  }

  def unfollow(id: UUID) = AuthenticatedAction { implicit request =>
      implicit val user = request.user

      user match {
        case Some(loggedInUser) => {
          collections.get(id) match {
            case Some(collection) => {
              events.addObjectEvent(user, id, collection.name, EventType.UNFOLLOW_COLLECTION.toString)
              collections.removeFollower(id, loggedInUser.id)
              userService.unfollowCollection(loggedInUser.id, id)
              Ok
            }
            case None => {
              NotFound
            }
          }
        }
        case None => {
          Unauthorized
        }
      }
  }

  def getTopRecommendations(followeeUUID: UUID, follower: User): List[MiniEntity] = {
    val followeeModel = collections.get(followeeUUID)
    followeeModel match {
      case Some(followeeModel) => {
        val sourceFollowerIDs = followeeModel.followers
        val excludeIDs = follower.followedEntities.map(typedId => typedId.id) ::: List(followeeUUID, follower.id)
        val num = configuration.get[Int]("number_of_recommendations")
        userService.getTopRecommendations(sourceFollowerIDs, excludeIDs, num)
      }
      case None => {
        List.empty
      }
    }
  }

  def attachSubCollection(collectionId: UUID, subCollectionId: UUID) = PermissionAction(Permission.AddResourceToCollection, Some(ResourceRef(ResourceRef.collection, collectionId))) { implicit request =>
    collections.addSubCollection(collectionId, subCollectionId, Some(request.identity)) match {
      case Success(_) => {
        collections.get(collectionId) match {
          case Some(collection) => {
            collections.get(subCollectionId) match {
              case Some(sub_collection) => {
                events.addSourceEvent(Some(request.identity), sub_collection.id, sub_collection.name, collection.id, collection.name, "add_sub_collection")
                Ok(jsonCollection(collection))
              }
              case None => InternalServerError
            }
          }
          case None => InternalServerError
        }
      }
      case Failure(t) => InternalServerError
    }
  }

  def createCollectionWithParent() = PermissionAction(Permission.CreateCollection) (parse.json) { implicit request =>
    (request.body \ "name").asOpt[String].map{ name =>
      var c : Collection = null
      implicit val user = Some(request.identity)

      val description = (request.body \ "description").asOpt[String].getOrElse("")
      (request.body \ "space").asOpt[String] match {
        case None | Some("default") =>  c = Collection(name = name, description = description, created = new Date(), datasetCount = 0, childCollectionsCount = 0, author = request.identity, root_spaces = List.empty, stats = new Statistics())
        case Some(space) => if (spaces.get(UUID(space)).isDefined) {
          c = Collection(name = name, description = description, created = new Date(), datasetCount = 0, author = request.identity, spaces = List(UUID(space)), root_spaces = List(UUID(space)), stats = new Statistics())
        } else {
          BadRequest(toJson("Bad space = " + space))
        }
      }

      collections.insert(c) match {
        case Some(id) => {
          appConfig.incrementCount('collections, 1)
          c.spaces.map{ spaceId =>
            spaces.get(spaceId)}.flatten.map{ s =>
            spaces.addCollection(c.id, s.id, user)
            collections.addToRootSpaces(c.id, s.id)
            events.addSourceEvent(user, c.id, c.name, s.id, s.name, EventType.ADD_COLLECTION_SPACE.toString)
          }

          //do stuff with parent here
          (request.body \"parentId").asOpt[String] match {
            case Some(parentId) => {
              collections.get(UUID(parentId)) match {
                case Some(parentCollection) => {
                  collections.addSubCollection(UUID(parentId), UUID(id), user) match {
                    case Success(_) => {
                      Ok(toJson(Map("id" -> id)))
                    }
                  }
                }
                case None => {
                  Ok(toJson("No collection with parentId found"))
                }
              }
            }
            case None => {
              Ok(toJson("No parentId supplied"))
            }

          }
          Ok(toJson(Map("id" -> id)))
        }
        case None => Ok(toJson(Map("status" -> "error")))
      }

    }.getOrElse(BadRequest(toJson("Missing parameter [name]")))
  }

  def removeSubCollection(collectionId: UUID, subCollectionId: UUID, ignoreNotFound: String) =
    PermissionAction(Permission.RemoveResourceFromCollection, Some(ResourceRef(ResourceRef.collection, collectionId)), Some(ResourceRef(ResourceRef.collection, subCollectionId))) { implicit request =>
    collections.removeSubCollection(collectionId, subCollectionId, Try(ignoreNotFound.toBoolean).getOrElse(true)) match {
      case Success(_) => {
        collections.get(collectionId) match {
          case Some(collection) => {
            collections.get(subCollectionId) match {
              case Some(sub_collection) => {
                events.addSourceEvent(Some(request.identity), sub_collection.id, sub_collection.name, collection.id, collection.name, "remove_subcollection")
              }
            }
          }
        }
        Ok(toJson(Map("status" -> "success")))
      }
      case Failure(t) => InternalServerError
    }
  }

  /**
    * Adds a Root flag for a collection in a space
    */
  def setRootSpace(collectionId: UUID, spaceId: UUID)  = PermissionAction(Permission.AddResourceToSpace, Some(ResourceRef(ResourceRef.space, spaceId))) { implicit request =>
    Logger.debug("changing the value of the root flag")
    (collections.get(collectionId), spaces.get(spaceId)) match {
      case (Some(collection), Some(space)) => {
        spaces.addCollection(collectionId, spaceId, Some(request.identity))
        collections.addToRootSpaces(collectionId, spaceId)
        events.addSourceEvent(Some(request.identity), collection.id, collection.name, space.id, space.name, EventType.ADD_COLLECTION_SPACE.toString)
        Ok(jsonCollection(collection))
      } case (None, _) => {
        Logger.error("Error getting collection  " + collectionId)
        BadRequest(toJson(s"The given collection id $collectionId is not a valid ObjectId."))
      }
      case _ => {
        Logger.error("Error getting space  " + spaceId)
        BadRequest(toJson(s"The given space id $spaceId is not a valid ObjectId."))
      }
    }
  }

  /**
    * Remove root flag from a collection in a space
    */
  def unsetRootSpace(collectionId: UUID, spaceId: UUID)  = PermissionAction(Permission.AddResourceToSpace, Some(ResourceRef(ResourceRef.space, spaceId))) { implicit request =>
    Logger.debug("changing the value of the root flag")
    collections.get(collectionId) match {
      case Some(collection) => {
        collections.removeFromRootSpaces(collectionId, spaceId)
        Ok(jsonCollection(collection))
      } case None => {
        Logger.error("Error getting collection  " + collectionId)
        BadRequest(toJson(s"The given collection id $collectionId is not a valid ObjectId."))
      }
    }
  }

  def getRootCollections() = PermissionAction(Permission.ViewCollection) { implicit request =>
    val root_collections_list = for (collection <- collections.listAccess(100,Set[Permission](Permission.ViewCollection), Some(request.identity),true, true,false); if collections.hasRoot(collection)  )
      yield jsonCollection(collection)

    Ok(toJson(root_collections_list))
  }

  def getAllCollections(limit : Int, showAll: Boolean) = PermissionAction(Permission.ViewCollection) { implicit request =>
    val all_collections_list = for (collection <- collections.listAllCollections(request.identity, showAll, limit))
      yield jsonCollection(collection)
    Ok(toJson(all_collections_list))
  }

  def getTopLevelCollections() = PermissionAction(Permission.ViewCollection){ implicit request =>
    implicit val user = Some(request.identity)
    val count = collections.countAccess(Set[Permission](Permission.ViewCollection),user,true)
    val limit = count.toInt
    val top_level_collections = for (collection <- collections.listAccess(limit,Set[Permission](Permission.ViewCollection), user,true, true,false); if (collections.hasRoot(collection) || collection.parent_collection_ids.isEmpty))
      yield jsonCollection(collection)
    Ok(toJson(top_level_collections))
  }

  def getChildCollectionIds(collectionId: UUID) = PermissionAction(Permission.ViewCollection, Some(ResourceRef(ResourceRef.collection,collectionId))){implicit request =>
    collections.get(collectionId) match {
      case Some(collection) => {
        val childCollectionIds = collection.child_collection_ids
        Ok(toJson(childCollectionIds))
      }
      case None => BadRequest(toJson("collection not found"))
    }
  }

  def getParentCollectionIds(collectionId: UUID) = PermissionAction(Permission.ViewCollection, Some(ResourceRef(ResourceRef.collection,collectionId))){implicit request =>
    collections.get(collectionId) match {
      case Some(collection) => {
        val parentCollectionIds = collection.parent_collection_ids
        Ok(toJson(parentCollectionIds))
      }
      case None => BadRequest(toJson("collection not found"))
    }
  }

  def getChildCollections(collectionId: UUID) = PermissionAction(Permission.ViewCollection, Some(ResourceRef(ResourceRef.collection,collectionId))){implicit request =>
    collections.get(collectionId) match {
      case Some(collection) => {
        val childCollections = ListBuffer.empty[JsValue]
        val childCollectionObjs = collections.get(collection.child_collection_ids)
        childCollectionObjs.found.foreach(child_collection => {
          childCollections += jsonCollection(child_collection)
        })
        childCollectionObjs.missing.foreach(childCollectionId => {
          Logger.debug("No child collection with id : " + childCollectionId)
          // TODO: This kind of cleanup should go in a separate admin endpoint, not fix-as-we-go
          collections.removeSubCollectionId(childCollectionId,collection)
        })

        Ok(toJson(childCollections))
      }
      case None => BadRequest(toJson("collection not found"))
    }
  }

  def getParentCollections(collectionId: UUID) = PermissionAction(Permission.ViewCollection, Some(ResourceRef(ResourceRef.collection,collectionId))){implicit request =>
    collections.get(collectionId) match {
      case Some(collection) => {
        val parentCollections = ListBuffer.empty[JsValue]
        val parentCollectionObjs = collections.get(collection.parent_collection_ids)
        parentCollectionObjs.found.foreach(parent_collection => {
          parentCollections += jsonCollection(parent_collection)
        })
        parentCollectionObjs.missing.foreach(parentCollectionId => {
          Logger.debug("No parent collection with id : " + parentCollectionId)
          // TODO: This kind of cleanup should go in a separate admin endpoint, not fix-as-we-go
          collections.removeParentCollectionId(parentCollectionId,collection)
        })

        Ok(toJson(parentCollections))
      }

      case None => BadRequest(toJson("collection not found"))
    }
  }

  def removeFromSpaceAllowed(collectionId: UUID , spaceId : UUID) = PermissionAction(Permission.AddResourceToSpace, Some(ResourceRef(ResourceRef.space, spaceId))) { implicit request =>
    val hasParentInSpace = collections.hasParentInSpace(collectionId, spaceId)
    Ok(toJson(!hasParentInSpace))
  }

  def download(id: UUID, compression: Int) = PermissionAction(Permission.DownloadFiles, Some(ResourceRef(ResourceRef.collection, id))) { implicit request =>
    collections.get(id) match {
      case Some(collection) => {
        val bagit = configuration.get[Boolean]("downloadCollectionBagit")
        // Use custom enumerator to create the zip file on the fly
        // Use a 1MB in memory byte array
        Ok.chunked(streamCollection(collection,1024*1024, compression, bagit, Some(request.identity))).withHeaders(
          "Content-Type" -> "application/zip",
          "Content-Disposition" -> ("attachment; filename=\"" + collection.name+ ".zip\"")
        )
      }
      // If the dataset wasn't found by ID
      case None => {
        NotFound
      }
    }
  }

  /**
   * Loop over all datasets in a collection and return chunks for the result zip file that will be
   * streamed to the client. The zip files are streamed and not stored on disk.
   *
   * @param collection dataset from which to get teh files
   * @param chunkSize chunk size in memory in which to buffer the stream
   * @param compression java built in compression value. Use 0 for no compression.
   * @return Source to produce array of bytes from a zipped stream containing the bytes of each file
   *         in the dataset
   */
  def streamCollection(collection: Collection, chunkSize: Int = 1024 * 8, compression: Int = Deflater.DEFAULT_COMPRESSION,
                       bagit: Boolean, user: Option[User]): Source[Array[Byte], NotUsed] = {

    // Keep two MD5 checksum lists, one for collection files and one for BagIt files
    val md5Files = HashMap.empty[String, MessageDigest]
    val md5Bag = HashMap.empty[String, MessageDigest]
    var totalBytes = 0L
    val byteArrayOutputStream = new ByteArrayOutputStream(chunkSize)
    val zip = new ZipOutputStream(byteArrayOutputStream)
    var bytesSet = false

    val current_iterator = new CollectionIterator(collection.name, collection, zip, md5Files, md5Bag, user, bagit)
    var is = current_iterator.next()

    Source.unfoldAsync(is) { currChunk =>
      currChunk match {
        case Some(inputStream) => {
          if (current_iterator.isBagIt() && bytesSet == false) {
            current_iterator.setBytes(totalBytes)
            bytesSet = true
          }
          val buffer = new Array[Byte](chunkSize)
          val bytesRead = scala.concurrent.blocking {
            inputStream.read(buffer)
          }
          val chunk = bytesRead match {
            case -1 => {
              zip.closeEntry()
              inputStream.close()
              Some(byteArrayOutputStream.toByteArray)
              if (current_iterator.hasNext())
                is = current_iterator.next()
              else {
                zip.close()
                is = None
              }
              Some(byteArrayOutputStream.toByteArray)
            }
            case read => {
              if (!current_iterator.isBagIt())
                totalBytes += bytesRead
              zip.write(buffer, 0, read)
              Some(byteArrayOutputStream.toByteArray)
            }
          }
          byteArrayOutputStream.reset()
          Future.successful(Some((is, chunk.get)))
        }
        case None => Future.successful(None)
      }
    }
  }
}
