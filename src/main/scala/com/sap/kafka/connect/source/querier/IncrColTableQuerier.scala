package com.sap.kafka.connect.source.querier

import com.sap.kafka.client.hana.HANAJdbcClient
import com.sap.kafka.connect.config.BaseConfig
import com.sap.kafka.connect.source.SourceConnectorConstants
import com.sap.kafka.connect.source.querier.QueryMode.QueryMode
import org.apache.kafka.common.config.ConfigException
import org.apache.kafka.connect.source.SourceRecord

import scala.collection.JavaConverters._

class IncrColTableQuerier(mode: QueryMode, table: String, tablePartition: Int, topic: String,
                          incrementingColumn: String, offsetMap: Map[String, Object],
                          config: BaseConfig, jdbcClient: Option[HANAJdbcClient])
      extends TableQuerier(mode, table, topic, config, jdbcClient) {

  private var incrColumn: String = _
  private var incrColumnType: Int = java.sql.Types.INTEGER

  if (incrementingColumn != null && incrementingColumn.nonEmpty) {
    incrColumn = getIncrementingColumn(incrementingColumn)
  } else {
    throw new IllegalArgumentException("Incrementing column not specified")
  }

  var offset = TimestampIncrementingOffset(offsetMap, incrColumn, incrColumnType)

  override def createQueryString(): Unit = {
    val builder = new StringBuilder()

    mode match {
      case QueryMode.TABLE =>
        if (tablePartition > 0) {
          builder.append(s"select * from $tableName PARTITION($tablePartition)")
        } else {
          builder.append(s"select * from $tableName")
        }
    }

    builder.append(" WHERE ")
    builder.append("\"" + incrColumn + "\"")
    builder.append(s" > ${TimestampIncrementingOffset.convertToTableDataType(
      offset.getIncrementingOffset(), incrColumnType)}")
    builder.append(" ORDER BY ")
    builder.append("\"" + incrColumn + "\"")
    builder.append(" ASC")

    queryString = Some(builder.toString())
  }

  override def extractRecords(): List[SourceRecord] = {
    if (resultList.isDefined) {
      resultList.get.map(record => {
        var partition: Map[String, String] = null

        if (incrementingColumn != null) {
          val id = record.get(incrementingColumn).toString
          offset = new TimestampIncrementingOffset(id)
        }

        mode match {
          case QueryMode.TABLE =>
            val partitionName = tableName + tablePartition.toString
            partition = Map(SourceConnectorConstants.TABLE_NAME_KEY -> partitionName)
          case _ => throw new ConfigException(s"Unexpected Query Mode: $mode")
        }
        new SourceRecord(partition.asJava, offset.toMap(), topic,
          getPartition(tablePartition, topic), record.schema(), record)
      })
    } else List()
  }

  override def toString: String = "IncrColTableQuerier{" +
    "name='" + table + "'" +
    ", topic='" + topic + "'}"

  private def getIncrementingColumn(incrementingCol: String): String = {
    val metadata = getOrCreateJdbcClient().get.getMetaData(table, None)

    metadata.foreach(metaAttr => {
      if (metaAttr.name.equals(incrementingCol)) {
        if (metaAttr.dataType == java.sql.Types.INTEGER ||
          metaAttr.dataType == java.sql.Types.BIGINT ||
          metaAttr.dataType == java.sql.Types.FLOAT ||
          metaAttr.dataType == java.sql.Types.DOUBLE ||
          metaAttr.dataType == java.sql.Types.DECIMAL ||
          metaAttr.dataType == java.sql.Types.REAL ||
          metaAttr.dataType == java.sql.Types.DATE ||
          metaAttr.dataType == java.sql.Types.TIME ||
          metaAttr.dataType == java.sql.Types.TIMESTAMP) {
          incrColumnType = metaAttr.dataType
          return metaAttr.name
        }
      }
    })
    throw new IllegalArgumentException("The Incrementing column is not found in the " +
      "table or is not of correct type")
  }

  /**
    * if no. of table partition exceeds no. of topic partitions,
    * this just takes the highest available topic partition to write.
    */
  private def getPartition(tablePartition: Int, topic: String): Int = {
    val topicProperties = config.topicProperties(topic)
    val maxPartitions = topicProperties("partition.count").toInt
    tablePartition % maxPartitions
  }
}