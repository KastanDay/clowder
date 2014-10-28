package services

import play.api.Play.current
import com.google.inject.Guice
import com.google.inject.AbstractModule

/**
 * Guice module configuration.
 *
 * @author Luigi Marini
 *
 */
object DI {
    lazy val injector = Guice.createInjector(new ConfigurationModule)
}

/**
 * Default production module.
 */
class ConfigurationModule extends AbstractModule {
  protected def configure() {
    bind(classOf[AppConfigurationService]).to(get("service.appConfiguration", "services.mongodb.MongoDBAppConfigurationService"))

    bind(classOf[DatasetService]).to(get("service.datasets", "services.mongodb.MongoDBDatasetService"))
    bind(classOf[FileService]).to(get("service.files", "services.mongodb.MongoDBFileService"))
    bind(classOf[CollectionService]).to(get("service.collections", "services.mongodb.MongoDBCollectionService"))

    bind(classOf[TagService]).to(get("service.tags", "services.mongodb.MongoDBTagService"))
    bind(classOf[SectionService]).to(get("service.sections", "services.mongodb.MongoDBSectionService"))
    bind(classOf[CommentService]).to(get("service.comments", "services.mongodb.MongoDBCommentService"))
    bind(classOf[PreviewService]).to(get("service.previews", "services.mongodb.MongoDBPreviewService"))

    bind(classOf[ExtractionService]).to(get("service.extractions", "services.mongodb.MongoDBExtractionService"))
    bind(classOf[ExtractorService]).to(get("service.extractors", "services.mongodb.MongoDBExtractorService"))
    bind(classOf[ExtractionRequestsService]).to(get("service.extractionRequests", "services.mongodb.MongoDBExtractionRequestsService"))

    bind(classOf[MultimediaQueryService]).to(get("service.multimediaQuery", "services.mongodb.MongoDBMultimediaQueryService"))

    bind(classOf[TempFileService]).to(get("service.tempFiles", "services.mongodb.MongoDBTempFileService"))

    bind(classOf[ThreeDService]).to(get("service.3D", "services.mongodb.MongoDBThreeDService"))
    bind(classOf[ThumbnailService]).to(get("service.thumbnails", "services.mongodb.MongoDBThumbnailService"))
    bind(classOf[TileService]).to(get("service.tiles", "services.mongodb.MongoDBTileService"))
    bind(classOf[CensusService]).to(get("service.census", "services.mongodb.MongoDBCensusService"))    

    bind(classOf[RdfSPARQLService]).to(get("service.rdfSparql", "services.fourstore.FourStoreRdfSPARQLService"))
  }

  protected def get[T](key: String, missing: String) : Class[T] = {
    val name = current.configuration.getString(key).getOrElse(missing)
    Class.forName(name).asInstanceOf[Class[T]]
  }
}
