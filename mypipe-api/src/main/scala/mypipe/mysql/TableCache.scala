package mypipe.mysql

import mypipe.api.data.{ColumnMetadata, PrimaryKey, Table}
import mypipe.api.event.TableMapEvent
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import akka.pattern.ask
import akka.util.Timeout
import mypipe.util.Actors

import scala.compat.Platform

/** A cache for tables whose metadata needs to be looked up against
 *  the database in order to determine column and key structure.
 *
 *  @param hostname of the database
 *  @param port of the database
 *  @param username used to authenticate against the database
 *  @param password used to authenticate against the database
 *  @param timeoutSeconds maximum time to wait for the table metadata request to return
 */
class TableCache(hostname: String, port: Int, username: String, password: String, timeoutSeconds: Int) {
  protected val system = Actors.actorSystem
  protected implicit val ec = system.dispatcher
  protected val tablesById = scala.collection.mutable.HashMap[Long, Table]()
  protected val tableNameToId = scala.collection.mutable.HashMap[String, Long]()
  protected lazy val dbMetadata = system.actorOf(MySQLMetadataManager.props(hostname, port, username, Some(password)), s"DBMetadataActor-$hostname:$port-${System.nanoTime()}")
  protected val log = LoggerFactory.getLogger(getClass)

  def getTable(tableId: Long): Option[Table] = {
    tablesById.get(tableId)
  }

  def refreshTable(tableId: Long): Option[Table] = {
    // FIXME: if the table is not in the map we can't refresh it.
    tablesById.get(tableId).flatMap(refreshTable)
  }

  def refreshTable(database: String, table: String): Option[Table] = {
    // FIXME: if the table is not in the map we can't refresh it.
    tableNameToId.get(database + table).flatMap(refreshTable)
  }

  def refreshTable(table: Table): Option[Table] = {
    // FIXME: if the table is not in the map we can't refresh it.
    Some(addTable(table.id, table.db, table.name, flushCache = true))
  }

  def addTableByEvent(ev: TableMapEvent, flushCache: Boolean = false): Table = {
    addTable(ev.tableId, ev.database, ev.tableName, flushCache)
  }

  def addTable(tableId: Long, database: String, tableName: String, flushCache: Boolean): Table = {

    if (flushCache) {

      val table = lookupTable(tableId, database, tableName)
      tablesById.put(tableId, table)
      tableNameToId.put(table.db + table.name, table.id)
      table

    } else {

      tablesById.getOrElseUpdate(tableId, {
        val table = lookupTable(tableId, database, tableName)
        tableNameToId.put(table.db + table.name, table.id)
        table
      })

    }
  }

  private def lookupTable(tableId: Long, database: String, tableName: String): Table = {

    implicit val timeout = Timeout(timeoutSeconds.seconds)

    val future = ask(dbMetadata, GetColumns(database, tableName, flushCache = true)).asInstanceOf[Future[(List[ColumnMetadata], Option[PrimaryKey])]]

    // FIXME: handle timeout
    val columns = Await.result(future, timeoutSeconds.seconds)

    Table(tableId, tableName, database, columns._1, columns._2)
  }
}
