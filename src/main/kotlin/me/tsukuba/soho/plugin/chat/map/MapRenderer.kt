package me.tsukuba.soho.plugin.chat.map

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bukkit.Chunk
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.configuration.ConfigurationSection
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.time.Instant
import java.util.logging.Logger
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.math.absoluteValue

data class IntBoundary(val x: Int, val y: Int, val w: Int, val h: Int) {
    val blockBoundary: IntBoundary get() = IntBoundary(
        x * 16 - if (x < 0) 0 else 1,
        y * 16 - if (y < 0) 0 else 1,
        w * 16,
        h * 16)
}

class MapRenderer(maps: ConfigurationSection, rootPath: Path) {
    private val colors: ConfigurationSection
        = maps.getConfigurationSection("colors") ?: maps.createSection("colors")
    val dataPath: Path

    companion object {
        val logger: Logger = Logger.getLogger(MapRenderer::class.qualifiedName)
    }

    init {
        maps.addDefault("save_path", "images/maps")
        dataPath = rootPath.resolve(maps.getString("save_path") ?: "images/maps")

        if (!dataPath.exists()) {
            dataPath.createDirectories()
        } else if (!dataPath.isDirectory()) {
            throw FileAlreadyExistsException(dataPath.toFile())
        }

        initializeColorMap(colors)
    }

    private suspend fun collectChunks(world: World) = sequence {
        val center = world.spawnLocation.chunk
        var chunks = 0
        var boundary = 1
        val centerAt = arrayOf(center.x, center.z)
        val at = arrayOf(centerAt[0], centerAt[1])
        val dir = arrayOf(1, 0)
        var stop = true

        logger.info("collecting chunks")

        while (chunks < world.chunkCount) {
            if (world.isChunkGenerated(at[0], at[1])) {
                logger.info(
                    "found generated chunk at (${at[0]}, ${at[1]}) / ${chunks + 1} of max ${world.chunkCount} chunks"
                )
                yield(world.getChunkAt(at[0], at[1]))
                stop = false
                chunks++
            }

            if (
                (at[0] + dir[0] - centerAt[0]).absoluteValue == boundary ||
                (at[1] + dir[1] - centerAt[0]).absoluteValue == boundary
            ) {
                when {
                    dir[0] == 1 -> {
                        if (stop) {
                            break
                        }
                        stop = true
                        boundary++
                        dir[0] = 0
                        dir[1] = -1
                    }
                    dir[0] == -1 -> {
                        dir[0] = 0
                        dir[1] = 1
                    }
                    dir[1] == 1 -> {
                        dir[0] = 1
                        dir[1] = 0
                    }
                    dir[1] == -1 -> {
                        dir[0] = -1
                        dir[1] = 0
                    }
                }
            }
            at[0] += dir[0]
            at[1] += dir[1]
        }

        logger.info("$chunks of chunks collected")
    }

    suspend fun renderMap(world: World): Path = withContext(Dispatchers.Default) {
        val chunks = collectChunks(world).toList()
        val boundary = chunks.findBoundary().blockBoundary
        val img = BufferedImage(boundary.w, boundary.h, BufferedImage.TYPE_INT_ARGB)
        val cr = img.raster

        for (x in 0 until boundary.w) {
            for (y in 0 until boundary.h) {
                cr.setPixel(x, y, intArrayOf(0, 0, 0, 0))
            }
        }

        for ((index, chunk) in chunks.withIndex()) {
            for (x in 0..15) {
                for (y in 0..15) {
                    for (z in world.maxHeight downTo world.minHeight) {
                        val block: Block = chunk.getBlock(x, y, z)
                        val key: NamespacedKey = block.type.key
                        if (block.type.isAir) {
                            continue
                        }
                        val col = colors.getColor(key.toString()) ?: continue
                        val (px, py) = (x to y).toPixelAt(boundary)
                        cr.setPixel(px, py, intArrayOf(col.red, col.green, col.blue, 0xff))
                    }
                }
            }

            logger.info("rendering ${index + 1} of ${chunks.size} chunks has completed")
        }

        val imgPath = dataPath.resolve("${world.name}-${Instant.now().epochSecond}.png")

        withContext(Dispatchers.IO) {
            logger.info("saving the generated map image to $imgPath")
            ImageIO.write(img, "png", imgPath.toFile())
        }

        logger.info("generating map image has done")
        imgPath
    }
}

fun Pair<Int, Int>.toPixelAt(blockBoundary: IntBoundary): Pair<Int, Int>
    = (first - blockBoundary.x) to (second - blockBoundary.y)

fun List<Chunk>.findBoundary(): IntBoundary {
    if (isEmpty()) {
        return IntBoundary(0, 0, -1, -1)
    }

    val xs = this.map { it.x }.sorted()
    val zs = this.map { it.z }.sorted()
    val x = xs.first()
    val y = zs.first()

    return IntBoundary(x, y, xs.last() - x + 1, zs.last() - x + 1)
}
