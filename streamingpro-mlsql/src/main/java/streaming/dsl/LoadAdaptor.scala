/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package streaming.dsl

import org.apache.spark.sql.{DataFrame, DataFrameReader, functions => F}
import streaming.core.datasource._
import streaming.dsl.auth.TableType
import streaming.dsl.load.batch.{AutoWorkflowSelfExplain, ModelSelfExplain}
import streaming.dsl.parser.DSLSQLParser._
import streaming.dsl.template.TemplateMerge
import streaming.source.parser.{SourceParser, SourceSchema}

/**
  * Created by allwefantasy on 27/8/2017.
  */
class LoadAdaptor(scriptSQLExecListener: ScriptSQLExecListener) extends DslAdaptor {

  def evaluate(value: String) = {
    TemplateMerge.merge(value, scriptSQLExecListener.env().toMap)
  }

  override def parse(ctx: SqlContext): Unit = {
    var format = ""
    var option = Map[String, String]()
    var path = ""
    var tableName = ""
    (0 to ctx.getChildCount() - 1).foreach { tokenIndex =>
      ctx.getChild(tokenIndex) match {
        case s: FormatContext =>
          format = s.getText
        case s: ExpressionContext =>
          option += (cleanStr(s.qualifiedName().getText) -> evaluate(getStrOrBlockStr(s)))
        case s: BooleanExpressionContext =>
          option += (cleanStr(s.expression().qualifiedName().getText) -> evaluate(getStrOrBlockStr(s.expression())))
        case s: PathContext =>
          path = s.getText

        case s: TableNameContext =>
          tableName = evaluate(s.getText)
        case _ =>
      }
    }

    def isStream = {
      scriptSQLExecListener.env().contains("streamName")
    }

    if (isStream) {
      scriptSQLExecListener.addEnv("stream", "true")
    }

    def isStreamSource(name: String) = {
      (TableType.KAFKA.includes ++ TableType.SOCKET.includes ++ List("mockStream")).contains(name)
    }

    if (isStream && (isStreamSource(format) || option.getOrElse("stream.source", "false").toBoolean)) {
      new StreamLoadAdaptor(scriptSQLExecListener, option, path, tableName, format).parse
    } else {
      new BatchLoadAdaptor(scriptSQLExecListener, option, path, tableName, format).parse
    }
    scriptSQLExecListener.setLastSelectTable(tableName)

  }
}


class BatchLoadAdaptor(scriptSQLExecListener: ScriptSQLExecListener,
                       option: Map[String, String],
                       var path: String,
                       tableName: String,
                       format: String
                      ) extends DslTool {
  def parse = {
    var table: DataFrame = null
    val sparkSession = scriptSQLExecListener.sparkSession
    val reader = scriptSQLExecListener.sparkSession.read
    reader.options(option)
    path = TemplateMerge.merge(path, scriptSQLExecListener.env().toMap)

    def emptyDataFrame = {
      import sparkSession.implicits._
      Seq.empty[String].toDF("name")
    }

    val dsConf = DataSourceConfig(cleanStr(path), option, Option(emptyDataFrame))
    var sourceInfo: Option[SourceInfo] = None

    DataSourceRegistry.fetch(format, option).map { datasource =>
      table = datasource.asInstanceOf[ {def load(reader: DataFrameReader, config: DataSourceConfig): DataFrame}].
        load(reader, dsConf)

      // extract source info if the datasource is  MLSQLSourceInfo
      if (datasource.isInstanceOf[MLSQLSourceInfo]) {
        val authConf = DataAuthConfig(dsConf.path, dsConf.config)
        sourceInfo = Option(datasource.asInstanceOf[MLSQLSourceInfo].sourceInfo(authConf))
      }

      // return the load table
      table
    }.getOrElse {
      // calculate resource real absolute path
      val resourcePath = resourceRealPath(scriptSQLExecListener, option.get("owner"), path)

      table = ModelSelfExplain(format, cleanStr(path), option, sparkSession).isMatch.thenDo.orElse(() => {

        AutoWorkflowSelfExplain(format, cleanStr(path), option, sparkSession).isMatch.thenDo().orElse(() => {
          reader.format(format).load(resourcePath)
        }).get()

      }).get
    }

    // In order to control the access of columns, we should rewrite the final sql (conver * to specify column names)
    table = rewriteOrNot(table, dsConf, sourceInfo, ScriptSQLExec.context())
    table.createOrReplaceTempView(tableName)
  }

  def rewriteOrNot(df: DataFrame,
                   config: DataSourceConfig,
                   sourceInfo: Option[SourceInfo],
                   context: MLSQLExecuteContext): DataFrame = {
    val rewrite = df.sparkSession
      .sparkContext
      .getConf
      .getBoolean("spark.mlsql.enable.datasource.rewrite", false)

    val implClass = df.sparkSession
      .sparkContext
      .getConf
      .get("spark.mlsql.datasource.rewrite.implClass", "")

    if (rewrite && implClass != "") {
      val instance = Class.forName(implClass)
      instance.newInstance()
        .asInstanceOf[RewriteableSource]
        .rewrite(df, config, sourceInfo, context)

    } else {
      df
    }
  }
}

class StreamLoadAdaptor(scriptSQLExecListener: ScriptSQLExecListener,
                        option: Map[String, String],
                        var path: String,
                        tableName: String,
                        format: String
                       ) extends DslTool {

  def withWaterMark(table: DataFrame, option: Map[String, String]) = {
    if (option.contains("eventTimeCol")) {
      table.withWatermark(option("eventTimeCol"), option("delayThreshold"))
    } else {
      table
    }

  }

  def parse = {
    var table: DataFrame = null
    val reader = scriptSQLExecListener.sparkSession.readStream
    val cPath = cleanStr(path)
    format match {
      case "kafka" | "socket" =>
        if (!cPath.isEmpty) {
          reader.option("subscribe", cPath)
        }
        table = reader.options(option).format(format).load()
      case "kafka8" | "kafka9" =>
        val format = "com.hortonworks.spark.sql.kafka08"
        /*
           kafka.bootstrap.servers
           kafka.metadata.broker
           startingoffset smallest
         */
        if (!cPath.isEmpty) {
          reader.option("topics", cPath)
        }
        table = reader.format(format).options(option).load()
      case "mockStream" =>
        val format = "org.apache.spark.sql.execution.streaming.mock.MockStreamSourceProvider"
        table = reader.format(format).options(option + ("path" -> cleanStr(path))).load()
      case _ =>
        val provider = option.getOrElse("provider", format)
        table = reader.format(provider).options(option).load()
    }
    table = withWaterMark(table, option)

    if (option.contains("valueSchema") && option.contains("valueFormat")) {
      val kafkaFields = List("key", "partition", "offset", "timestamp", "timestampType", "topic")
      val keepOriginalValue = if (option.getOrElse("keepValue", "false").toBoolean) List("value") else List()
      val sourceSchema = new SourceSchema(option("valueSchema"))
      val sourceParserInstance = SourceParser.getSourceParser(option("valueFormat"))

      table = table.withColumn("kafkaValue", F.struct(
        (kafkaFields ++ keepOriginalValue).map(F.col(_)): _*
      )).selectExpr("CAST(value AS STRING) as tmpValue", "kafkaValue")
        .select(sourceParserInstance.parse(F.col("tmpValue"), sourceSchema = sourceSchema, Map()).as("data"), F.col("kafkaValue"))
        .select("data.*", "kafkaValue")
    }

    path = TemplateMerge.merge(path, scriptSQLExecListener.env().toMap)
    table.createOrReplaceTempView(tableName)
  }
}
