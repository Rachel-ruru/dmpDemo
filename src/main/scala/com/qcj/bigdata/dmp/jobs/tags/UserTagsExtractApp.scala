package com.qcj.bigdata.dmp.jobs.tags

import java.util

import com.qcj.bigdata.dmp.tags._
import com.qcj.bigdata.dmp.util.{HBaseConnectionUtil, Utils}
import org.apache.hadoop.hbase.TableName
import org.apache.hadoop.hbase.client.Put
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Row, SparkSession}

import scala.collection.mutable

/**
  * 用户标签提取应用
  */
/* 步骤：
  *  创建字典映射表：
  */
object UserTagsExtractApp {
  def main(args: Array[String]): Unit = {
    /*if(args == null || args.length < 5) {
      println(
        """Parameter Errors! Usage: <appmapping> <devicemapping> <userTable> <adlogs> <target>
          |appmapping      :   appmapping
          |devicemapping   :   devicemapping
          |userTable       ：  userTable
          |adlogs          :   adlogs
          |target          ：  target
        """.stripMargin)
      System.exit(-1)
    }
    val Array(appmapping, devicemapping, userTable, adlogs, target) = args*/

    val conf = new SparkConf()
      .setAppName("UserTagsExtractApp")
      .setMaster("local[2]")

    val spark = SparkSession.builder()
      .config(conf)
      .getOrCreate()

    //加载广告数据
    //val adLogsDF = spark.read.parquet(adlogs)
    val adLogsDF = spark.read.parquet("data/out/")
    adLogsDF.show()
    //用户对应的多个标签
    val userid2TagsRDD:RDD[(String, mutable.Map[String, Int])] = adLogsDF.rdd.map{case row => {
        //在这里面完成的标签的提取
//        val userId = row.getAs[String]("userid")//平台用户id
      //当前用户的唯一的标识
      val userId = getNotEmptyID(row).getOrElse("UnKnow-User")

        //返回标签规定好的格式数据：LC_0->1
        //1.广告位标签
        val positionTags:mutable.Map[String, Int] = AdPositionTags.extractTag(row)//根据广告位类型（adspacetype）返回元组
        //2.APP标签
        val appNameTags:mutable.Map[String, Int] = AppNameTags.extractTag(row)
        //3.渠道标签
        val channelTags:mutable.Map[String, Int] = ChannelTags.extractTag(row)
        //4.设备：操作系统|联网方式|运营商  标签
        val deviceTags:mutable.Map[String, Int]  = DeviceTags.extractTag(row)
        //5.关键字标签
        val keywordTags:mutable.Map[String, Int] = KeyWordTags.extractTag(row)
        //6.地域标签
        val zoneTags:mutable.Map[String, Int] = ZoneTags.extractTag(row)
      //将所有用户对应的标签MAP合并(标签1->1,标签2->2,标签3->3)
//      val tagsMap = positionTags.++(appNameTags).++(channelTags)
      val tagsMap = Utils.addTags(positionTags, appNameTags, channelTags, deviceTags, keywordTags, zoneTags)
//      println(s"-----------------${userId}")
//      println(s"-----------------${row.fieldIndex("userid")}")
      (userId, tagsMap)//用户：标签
    }}
    //(1,Map(ZC_上海市 -> 1, APP_马上赚 -> 1, ZP_上海市 -> 1, DEVICE_NETWORK_D0002001 -> 1, LC_02 -> 1, DEVICE_ISP_D0003004 -> 1, DEVICE_OS_D0001001 -> 1, CN_ -> 0))
//    userid2TagsRDD.foreach(println)


    //将标签进行合并
    //wordcount----->reduceByKey() <user, map>
    val useridTagsRDD:RDD[(String, mutable.Map[String, Int])] = userid2TagsRDD.reduceByKey{case (map1, map2) => {
      for((tag, count) <- map2) {//第二个中的
        map1.put(tag, map1.getOrElse(tag, 0) + count)//map1中的值加上map2中的值相加之后放入map1中
        //                val map1OldValue = map1.get(tag)
        //                map1.put(tag, count + map1OldValue.getOrElse(0))
      }
      map1
    }}
    //(2,Map(LC_02 -> 2, DEVICE_NETWORK_D0002001 -> 2, DEVICE_ISP_D0003004 -> 2, ZC_益阳市 -> 2, CN_ -> 0, APP_其他 -> 2, DEVICE_OS_D0001001 -> 2, ZP_湘南省 -> 2))
    //useridTagsRDD.foreach(println)


    /**
      * 因为标签的动态化，所以很难找到一种传统的数据来进行存储，只能使用非关系型数据，可以动态的添加列内容
      * hbase、es等待
      * spark去操作hbase，就将hbase当做mysql去处理，所以spark怎么操作mysql，就怎么操作hbase
      * 启动hbase:启动命令：  start-hbase.sh
      * 创建hbase数据库：     create 'dmp_1807', 'cf'
      * 去hbase中查看：  scan 'dmp_1807'
      * 重新运行会把原来的数据给覆盖掉，但是有时间戳记录版本
      */
    useridTagsRDD.foreachPartition(partition => {
      if(!partition.isEmpty) {
        val connection = HBaseConnectionUtil.getConnection()
        val table = connection.getTable(TableName.valueOf("dmp_1807"))
        partition.foreach{case (userid, tags) => {
          //import to hbase
          val puts = new util.ArrayList[Put]()
          //(tag, counts)：ZC_上海市 -> 2
          for((tag, counts) <- tags) {
            val put = new Put(userid.getBytes)
            put.addColumn("cf".getBytes, tag.getBytes, (counts + "").getBytes)
            puts.add(put)
          }
          table.put(puts)
          table.close()
        }}
        connection.close()
      }
    })

    spark.stop()
  }

  // 获取用户唯一不为空的ID
  def getNotEmptyID(row: Row): Option[String] = {
    row match {
      case v if v.getAs[String]("userid").nonEmpty => Some("USERID:" + v.getAs[String]("userid").toUpperCase)
      case v if v.getAs[String]("imei").nonEmpty => Some("IMEI:" + v.getAs[String]("imei").replaceAll(":|-\\", "").toUpperCase)
      case v if v.getAs[String]("imeimd5").nonEmpty => Some("IMEIMD5:" + v.getAs[String]("imeimd5").toUpperCase)
      case v if v.getAs[String]("imeisha1").nonEmpty => Some("IMEISHA1:" + v.getAs[String]("imeisha1").toUpperCase)

      case v if v.getAs[String]("androidid").nonEmpty => Some("ANDROIDID:" + v.getAs[String]("androidid").toUpperCase)
      case v if v.getAs[String]("androididmd5").nonEmpty => Some("ANDROIDIDMD5:" + v.getAs[String]("androididmd5").toUpperCase)
      case v if v.getAs[String]("androididsha1").nonEmpty => Some("ANDROIDIDSHA1:" + v.getAs[String]("androididsha1").toUpperCase)

      case v if v.getAs[String]("mac").nonEmpty => Some("MAC:" + v.getAs[String]("mac").replaceAll(":|-", "").toUpperCase)
      case v if v.getAs[String]("macmd5").nonEmpty => Some("MACMD5:" + v.getAs[String]("macmd5").toUpperCase)
      case v if v.getAs[String]("macsha1").nonEmpty => Some("MACSHA1:" + v.getAs[String]("macsha1").toUpperCase)

      case v if v.getAs[String]("idfa").nonEmpty => Some("IDFA:" + v.getAs[String]("idfa").replaceAll(":|-", "").toUpperCase)
      case v if v.getAs[String]("idfamd5").nonEmpty => Some("IDFAMD5:" + v.getAs[String]("idfamd5").toUpperCase)
      case v if v.getAs[String]("idfasha1").nonEmpty => Some("IDFASHA1:" + v.getAs[String]("idfasha1").toUpperCase)

      case v if v.getAs[String]("openudid").nonEmpty => Some("OPENUDID:" + v.getAs[String]("openudid").toUpperCase)
      case v if v.getAs[String]("openudidmd5").nonEmpty => Some("OPENDUIDMD5:" + v.getAs[String]("openudidmd5").toUpperCase)
      case v if v.getAs[String]("openudidsha1").nonEmpty => Some("OPENUDIDSHA1:" + v.getAs[String]("openudidsha1").toUpperCase)
      case _ => None
    }
  }
}
