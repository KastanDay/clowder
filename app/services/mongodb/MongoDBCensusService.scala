package services.mongodb

//import services.{PreviewService, TileService, CensusService}
//import java.io.InputStream
//import com.mongodb.casbah.gridfs.GridFS
//import com.mongodb.casbah.commons.MongoDBObject
//import models.{UUID, Tile}
//import play.api.libs.json.{JsValue, JsObject}
//import org.bson.types.ObjectId
//import scala.Some
//import com.mongodb.WriteConcern
//import play.api.libs.json.Json._
//import scala.Some

import com.novus.salat.dao.{ModelCompanion, SalatDAO}
import MongoContext.context
import play.api.Play.current
import com.mongodb.casbah.Imports._
import play.api.Logger
import javax.inject.Singleton

import services.CensusService
import models.{CensusIndex, UUID}
import scala.util.Try
import scala.util.Success

/**
 * Created by Inna Zharnitsky on May 27, 2014
 */
class MongoDBCensusService extends CensusService {  
  
	/*
   * Check if index with this id is already in mongo collection. If it is - update type. Id it is not -
   * add a new entry with index id and index type.
   */
  def insertType (indexId: UUID, indexType:String){
	  Logger.debug("MongoDBCensusIndexService - top of insertType, indexId = " + indexId)  
	  val query = MongoDBObject("indexId" -> indexId.stringify)
	  //set "true" for upsert
	  val result = CensusIndexDAO.update(query,  $set("indexType" -> indexType), true, false, WriteConcern.Safe)	  
  }
  
  /*
   *Insert name of an index into mongo db.
   *Check if index with this id is already in mongo collection. If it is - update name. Id it is not -
   * add a new entry with index id and index name.
   * Input:
   * 	indexId - id of the index
   * 	indexName - name  of the index 
   * 
   */  
  def insertName (indexId: UUID, indexName:String){
	  Logger.debug("dao - top of insertName, indexId = "+indexId.stringify)	  
	 //Logger.debug("MongoDBCensusIndexService index as object = " + new ObjectId(indexId.stringify) )
	  val query = MongoDBObject("indexId" ->indexId.stringify)
	  Logger.debug("MongoDBCensusIndexService query = " + query)
	  //set boolean parameter to "true" for upsert, i.e. update/insert
	  val result = CensusIndexDAO.update(query,  $set("indexName" -> indexName), true, false, WriteConcern.Safe)
  
	  Logger.debug("MongoDBCensusIndexService end of insert name")
  }
  
 /**
   *Insert name and type of an index into mongo db.
   * Input:
   * 	indexId - id of the index
   * 	indexName - name  of the index 
   * 	indexType - type of the index
   * 
   */ /*def insertNameType (indexId: UUID, indexName:String, indexType:String){
	   Logger.debug("MongoDBCensusIndexService - top of insertnameType, indexId = " + indexId)
	   insert(indexId, Some(indexName), Some(indexType))
    	//val ind = CensusIndex(indexId, Option(indexType), None)
    	//	CensusIndexDAO.insert(ind)
  }*/
  
  /*private def insert (indexId: UUID, indexName:Option[String], indexType:Option[String]){
    Logger.debug("MongoDBCensusIndexService - top of insert, indexId = " + indexId)
    val ind = CensusIndex(indexId,  indexName, indexType)
    CensusIndexDAO.insert(ind)
    
  }*/
  
  
  def getName(indexId:UUID):Option[String]={
    Logger.debug("MongnDBCensusIndServ - top of  getIndexName id = " + indexId)    
    val query = MongoDBObject("indexId" ->indexId.stringify)
    //Logger.debug("MongnDBCensusIndServ get name - query = " + query)
    val name = CensusIndexDAO.findOne(query).flatMap(_.indexName)
    Logger.debug("MongnDBCensusIndServ - got name = " + name)
    name
  }
  
  def getType(indexId:UUID):Option[String]={    
    CensusIndexDAO.findOne(MongoDBObject("indexId" -> indexId.stringify)).flatMap(_.indexType)   
  }
  
   
  
  def isFound888(indexId: UUID): Boolean ={ 
		Logger.debug("MongnDBCensusIndServ - top of isFound, indexId = " + indexId)
		  CensusIndexDAO.findOne(MongoDBObject("indexId" -> indexId)) match{
			//CensusIndexDAO.findOne(MongoDBObject("indexId" -> new ObjectId("537e6d2b11bd3e052daa7d99"))) match{
			//CensusIndexDAO.findOne(MongoDBObject("indexId" -> new ObjectId("537e6d2b11bd3e052daa7d99"))) match{
			case Some(index)=>   
				  Logger.debug("MongnDBCensusIndServ - index.indexType = " + index.indexType)
				Logger.debug("MongnDBCensusIndServ - index.indexId = " +index.indexId)// id = " + cenIndex.)
				return true
			case None =>
				  
		  		Logger.debug("MongnDBCensusIndServ - isFound :  NO")
		  		return false
		  	}
          //return false   
  }
  
    /*
     * Removes colleciton from mongo db
     */
  def deleteAll(){
    Logger.debug("top of deleteALl")
    CensusIndexDAO.remove(MongoDBObject())
    //CensusIndexDAO.drop()
   }  
  
  /**
   * Removes one index from collection
   */
  def delete(indexId:UUID):Boolean={    
    Logger.debug("MongoDBCensusIndexService - top of delete for indexId = " + indexId)
    val query = MongoDBObject("indexId" -> indexId.stringify)
    val result = CensusIndexDAO.remove( query )
    Logger.debug("MongoDBCensusIndexService - delete - result =" + result.toString())
    Logger.debug("MongoDBCensusIndexService - delete: Number removed: = result.getN = " + result.getN)

   var wasDeleted:Boolean =   if (result.getN >0) true else false        
   wasDeleted
  }
}

object CensusIndexDAO extends ModelCompanion[CensusIndex, ObjectId] {
  val dao = current.plugin[MongoSalatPlugin] match {
    case None => throw new RuntimeException("No MongoSalatPlugin");
    case Some(x) => new SalatDAO[CensusIndex, ObjectId](collection = x.collection("censusIndex")) {}
  }
}