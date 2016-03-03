package unicredit.spark.hbase

import java.text.SimpleDateFormat
import java.util.Calendar

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hbase.client.{Connection, ConnectionFactory}
import org.apache.hadoop.hbase.mapreduce.TableOutputFormat
import org.apache.hadoop.hbase.util.Bytes
import org.apache.hadoop.hbase.{HColumnDescriptor, HTableDescriptor, TableName}
import org.apache.hadoop.mapreduce.Job
import org.apache.spark.rdd.RDD

/**
 * Utilities for dealing with HBase tables
 */
trait HBaseUtils {

  implicit def stringToBytes(s: String): Array[Byte] = Bytes.toBytes(s)
  implicit def arrayToBytes(a: Array[String]): Array[Array[Byte]] = a map Bytes.toBytes

  protected[hbase] def createJob(table: String, conf: Configuration) = {
    conf.set(TableOutputFormat.OUTPUT_TABLE, table)
    val job = Job.getInstance(conf, this.getClass.getName.split('$')(0))
    job.setOutputFormatClass(classOf[TableOutputFormat[String]])
    job
  }

  object Admin {
    def apply()(implicit config: HBaseConfig) = new Admin(ConnectionFactory.createConnection(config.get))
  }

  class Admin(connection: Connection) {
    /**
     * Closes the connection to HBase. Must the Admin instance is no more needed
     */
    def close = connection.close()

    /**
     * Checks if table exists, and requires that it contains the desired column family
     *
     * @param table name of the table
     * @param family name of the column family
     *
     * @return true if table exists, false otherwise
     */
    def tableExists(table: String, family: String): Boolean = {
      val admin = connection.getAdmin
      val tableName = TableName.valueOf(table)
      if (admin.tableExists(tableName)) {
        val families = admin.getTableDescriptor(tableName).getFamiliesKeys
        require(families.contains(family.getBytes), s"Table [$tableName] exists but column family [$family] is missing")
        true
      } else false
    }

    /**
     * Checks if table exists, and requires that it contains the desired column families
     *
     * @param table name of table
     * @param families set of column families
     *
     * @return true if table exists, false otherwise
     */
    def tableExists(table: String, families: Set[String]): Boolean = {
      val admin = connection.getAdmin
      val tableName = TableName.valueOf(table)
      if (admin.tableExists(tableName)) {
        val tfamilies = admin.getTableDescriptor(tableName).getFamiliesKeys
        for (family <- families)
          require(tfamilies.contains(family.getBytes), s"Table [$tableName] exists but column family [$family] is missing")
        true
      } else false
    }

    /**
     * Takes a snapshot of the table, the snapshot's name has format "tableName_yyyyMMddHHmmss"
     *
     * @param table name of table
     */
    def snapshot(table: String): Admin = {
      val sdf = new SimpleDateFormat("yyyyMMddHHmmss")
      val suffix = sdf.format(Calendar.getInstance().getTime)
      snapshot(table, s"${table}_$suffix")
      this
    }

    /**
     * Takes a snapshot of the table
     *
     * @param table name of table
     * @param snapshotName name of snapshot
     */
    def snapshot(table: String, snapshotName: String): Admin = {
      val admin = connection.getAdmin
      val tableDescriptor = new HTableDescriptor(TableName.valueOf(table))
      val tName = tableDescriptor.getTableName
      admin.snapshot(snapshotName, tName)
      this
    }

    /**
     * Creates a table (if it doesn't exist already) with one or more column families
     * and made of one or more regions
     *
     * @param tableName name of table
     * @param families set of column families
     * @param splitKeys ordered list of keys that defines region splits
     */
    def createTable(tableName: String, families: Set[String], splitKeys: Seq[String]): Admin = {
      val admin = connection.getAdmin
      val table = TableName.valueOf(tableName)
      if (!admin.isTableAvailable(table)) {
        val tableDescriptor = new HTableDescriptor(table)
        families foreach { f => tableDescriptor.addFamily(new HColumnDescriptor(f)) }
        if (splitKeys.isEmpty)
          admin.createTable(tableDescriptor)
        else {
          val splitKeysBytes = splitKeys.map(Bytes.toBytes).toArray
          admin.createTable(tableDescriptor, splitKeysBytes)
        }
      }
      this
    }

    /**
     * Creates a table (if it doesn't exist already) with one or more column families
     *
     * @param tableName name of table
     * @param families list of one or more column families
     */
    def createTable(tableName: String, families: String*): Admin =
      createTable(tableName, families.toSet, Seq.empty)

    /**
     * Creates a table (if it doesn't exist already) with a column family and made of one or more regions
     *
     * @param tableName name of table
     * @param family name of column family
     * @param splitKeys ordered list of keys that defines region splits
     */
    def createTable(tableName: String, family: String, splitKeys: Seq[String]): Admin =
      createTable(tableName, Set(family), splitKeys)
  }

  /**
   * Given a RDD of keys and the number of requested table's regions, returns an array
   * of keys that are start keys of the table's regions. The array length is
   * ''regionsCount-1'' since the start key of the first region is not needed
   * (since it does not determine a split)
   *
   * @param rdd RDD of strings representing row keys
   * @param regionsCount number of regions
   *
   * @return a sorted sequence of start keys
   */
  def computeSplits(rdd: RDD[String], regionsCount: Int): Seq[String] = {
    rdd.sortBy(s => s, numPartitions = regionsCount)
      .mapPartitions(_.take(1))
      .collect().toList.tail
  }
}
