package wiki.dig.db

import java.io._
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

import com.google.common.collect.Lists
import com.google.common.io.Files
import org.apache.commons.lang3.StringUtils
import org.rocksdb._
import org.slf4j.LoggerFactory
import wiki.dig.common.MyConf
import wiki.dig.db.ast.Db
import wiki.dig.repo.CategoryRepo
import wiki.dig.util.ByteUtil

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.Random

/**
  * wiki层级体系数据库，记录了层级之间的父子关系. 层级体系数据库依赖于CategoryDb主数据库
  * 事先构建成功。(see CategoryDb.build)
  */
object CategoryHierarchyDb extends Db {
  val LOG = LoggerFactory.getLogger(this.getClass)

  import StandardCharsets.UTF_8

  val dbPath = new File(MyConf.dbRootDir, "category/hierarchy")
  if (!dbPath.getParentFile.exists())
    dbPath.getParentFile.mkdirs()

  RocksDB.loadLibrary()

  val options = new DBOptions().setCreateIfMissing(true)
    .setMaxBackgroundCompactions(10)
    .setCreateMissingColumnFamilies(true)

  protected val cfNames = Lists.newArrayList[ColumnFamilyDescriptor](
    new ColumnFamilyDescriptor("default".getBytes(UTF_8)),
    new ColumnFamilyDescriptor("meta".getBytes(UTF_8)) //元数据族
  )

  protected val cfHandlers = Lists.newArrayList[ColumnFamilyHandle]

  protected val db = RocksDB.open(options, dbPath.getAbsolutePath, cfNames, cfHandlers)

  protected val defaultHandler: ColumnFamilyHandle = cfHandlers.get(0)
  protected val metaHandler: ColumnFamilyHandle = cfHandlers.get(1)

  val Max_Depth = 5


  def accept(name: String): Boolean = {
    val title = name.replaceAll("_", " ").toLowerCase()

    if (title.length > 7) { //保留1980s此类词条
      val startString = title.substring(0, 4)
      if (StringUtils.isNumeric(startString)) return false
    }

    //step 2: remove "list of xxxx" and "index of xxx"
    if (title.indexOf("index of ") >= 0 ||
      title.indexOf("list of") >= 0 ||
      title.indexOf("lists of") >= 0 || //新增加
      title.indexOf("(disambiguation)") >= 0) return false

    //以年份结尾的词条，符合年份时代结尾的形式文章，如``China national football team results (2000–09)''，因为这类文章的作用更类似于类别，起到信息组织的作用。
    val pattern = Pattern.compile("\\(\\d{4}(–|\\-)\\d{2,4}\\)$")
    if (pattern.matcher(title).find) return false

    return true
  }

  def build() = {
    val startNodes = Await.result(CategoryRepo.levelOne(), Duration.Inf).map(_.id)
    val queue = mutable.Queue.empty[(Int, Int)]

    var totalWeight: Long = 0L

    var counter = 0

    startNodes.foreach(id => queue.enqueue((id, 1)))

    startNodes.foreach(println)

    while (queue.nonEmpty) {
      val (cid, depth) = queue.dequeue()

      val key = ByteUtil.int2bytes(cid)

      if (getCNode(cid).isEmpty) {
        counter += 1
        if (counter % 1000 == 0) {
          println(s"processing $counter, queue size: ${queue.size}")
        }
        //之前没有保存过，已保证保存的depth最小。
        val outlinks = CategoryDb.getOutlinks(cid).filter {
          id =>
            CategoryDb.getNameById(id) match {
              case Some(name) =>
                accept(name)
              case None =>
                println(s"no name error: $id")
                false
            }
        }
        val weights = outlinks.map(CategoryDb.getLinkedCount(_))

        val weight = CategoryDb.getLinkedCount(cid)
        totalWeight += weight

        val node = CNode(depth, weight, outlinks, weights)
        db.put(key, node.toBytes())
        if (depth <= Max_Depth) {
          outlinks.foreach(id => queue.enqueue((id, depth + 1)))
        }
      } else {
        //println(s"$cid / $depth")
        print(".")
      }
    }

    db.put(metaHandler, "TotalWeight".getBytes(UTF_8), ByteUtil.long2bytes(totalWeight))
    println("DONE")
  }

  def getCNode(cid: Int): Option[CNode] = Option(
    db.get(ByteUtil.int2bytes(cid))
  ) map readCNode

  def getTotalWeight(): Long = Option(
    db.get(metaHandler, "TotalWeight".getBytes(UTF_8))
  ).map(ByteUtil.bytes2Long(_)).getOrElse(1)

  /**
    * 数据库名字
    */
  def dbName: String = "Category Hierarchy DB"

  override def close(): Unit = {
    print(s"==> Close Category Hierarchy Db ... ")
    cfHandlers.forEach(h => h.close())
    db.close()
    println("DONE.")
  }

  def readCNode(bytes: Array[Byte]): CNode = {
    val din = new DataInputStream(new ByteArrayInputStream(bytes))
    val depth = din.readInt()
    val weight = din.readInt()
    val count = din.readInt()
    val outlinks = (0 until count).map(_ => din.readInt())
    val weights = (0 until count).map(_ => din.readInt())
    din.close
    CNode(depth, weight, outlinks, weights)
  }

  /**
    * 抽样n个三角形
    *
    * @param n
    */
  def sample(n: Int): Unit = {
    val totalWeight = getTotalWeight().toInt

    val writer = Files.newWriter(new File("./triangle.txt"), UTF_8)

    //在每个节点上按比例抽样。
    val startNodes = Await.result(CategoryRepo.levelOne(), Duration.Inf).map(_.id)
    val queue = mutable.Queue.empty[(Int, Int)]

    var counter = 0

    startNodes.foreach(id => queue.enqueue((id, 1)))

    startNodes.foreach(println)

    println(s"total weight: $totalWeight")

    while (queue.nonEmpty) {
      val (cid, depth) = queue.dequeue()

      val key = ByteUtil.int2bytes(cid)

      getCNode(cid) match {
        case Some(node) =>
          counter += 1
          if (counter % 1000 == 0) {
            println(s"processing $counter, queue size: ${queue.size}")
          }
          val outlinks = node.outlinks

          if (outlinks.size >= 2) {
            //每个节点上都循环n次，但只有w*(max_depth - depth + 1)/totalWeights 的机率会被选中
            (0 until n) foreach {
              _ =>
                //抽样
                val r = Random.nextInt(totalWeight)
                if (r < node.weight) {
                  //被抽中了，再来抽子节点


                  val weights = node.weights
                  val childTotalWeights = weights.sum + 1

                  //挑选第一个子类
                  val x = pick(outlinks, weights, Random.nextInt(childTotalWeights))

                  def next(exceptValue: Int): Int = {
                    val v = pick(outlinks, weights, Random.nextInt(childTotalWeights))
                    if (v != exceptValue) v else next(exceptValue)
                  }

                  //挑选第2个子类，但不能和第一个重复
                  val y = next(x)

                  //output (cid, x, y)
                  val line = s"${CategoryDb.getNameById(cid).getOrElse("")}, ${CategoryDb.getNameById(x).getOrElse("")}, ${CategoryDb.getNameById(y).getOrElse("")}\n"
                  writer.write(line)
                  if (counter % 100 == 0) writer.flush()
                }
            }
          }

          //继续后续抽样处理
          if (depth <= Max_Depth) {
            outlinks.foreach(id => queue.enqueue((id, depth + 1)))
          }
        case None =>
          //Error
          print("X")
      }
    }

    writer.close()
  }

  /**
    * 从outlink中按概率随机挑选一个id
    *
    * @param outlinks
    * @param weights
    * @return
    */
  def pick(outlinks: Seq[Int], weights: Seq[Int], randNumber: Int): Int = {
    var accumulator = 0
    outlinks.zip(weights).find {
      case (id, w) =>
        if (w + accumulator > randNumber)
          true
        else {
          accumulator += w
          false
        }
    }.map(_._1).getOrElse(outlinks.head)
  }
}

/**
  * 类别节点
  *
  * @param depth    当前类别节点的深度
  * @param weight   当前类别的权重
  * @param outlinks 当前类别节点的出链，即下级子类
  * @param weights  对应子类的权重，当前为出入量数量+页面数量
  */
case class CNode(depth: Int,
                 weight: Int,
                 outlinks: Seq[Int],
                 weights: Seq[Int]
                ) {
  def toBytes(): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    val dos = new DataOutputStream(out)

    dos.writeInt(depth)
    dos.writeInt(weight)

    dos.writeInt(outlinks.size)
    outlinks.foreach(dos.writeInt(_))
    weights.foreach(dos.writeInt(_))
    dos.close()
    out.close()

    out.toByteArray
  }
}