package org.osmdroid.tileprovider.constants;

import java.io.File;

import org.osmdroid.tileprovider.LRUMapTileCache;

import android.os.Environment;
import java.util.ArrayList;
import java.util.List;

/**
 *
 * This class contains constants used by the tile provider.
 *
 * @author Neil Boyd
 *
 */
public class OpenStreetMapTileProviderConstants {

	public static boolean DEBUGMODE = false;
	public static final boolean DEBUG_TILE_PROVIDERS = false;

	/** Minimum Zoom Level */
	public static final int MINIMUM_ZOOMLEVEL = 0;

	/** Base path for osmdroid files. Zip/sqlite/mbtiles/etc files are in this folder. */
	final static File OSMDROID_PATH = new File(Environment.getExternalStorageDirectory(),
			"osmdroid");
     
     private final static List<File> OSMDROID_PATHS = new ArrayList<File>();
     static{
          OSMDROID_PATHS.add(OSMDROID_PATH);
          File f=new File("/storage/extSdCard/",
			"osmdroid");
          if (f.exists())
               OSMDROID_PATHS.add(f);
     }
     
	/** Base path for tiles. 
      /sdcard/osmdroid
      */
	public static File TILE_PATH_BASE = new File(OSMDROID_PATH, "tiles");

     /**
      * this is the path where all downloaded tiles are stored to
      * @since 4.4
      */
     public static File DEFAULT_CACHE_DIR=TILE_PATH_BASE;
      
	/** add an extension to files on sdcard so that gallery doesn't index them */
	public static final String TILE_PATH_EXTENSION = ".tile";

	/**
	 * Initial tile cache size. The size will be increased as required by calling
	 * {@link LRUMapTileCache#ensureCapacity(int)} The tile cache will always be at least 3x3.
	 */
	public static final int CACHE_MAPTILECOUNT_DEFAULT = 9;

	/**
	 * number of tile download threads, conforming to OSM policy:
	 * http://wiki.openstreetmap.org/wiki/Tile_usage_policy
	 */
	public static final int NUMBER_OF_TILE_DOWNLOAD_THREADS = 2;

	public static final int NUMBER_OF_TILE_FILESYSTEM_THREADS = 8;

	public static final long ONE_SECOND = 1000;
	public static final long ONE_MINUTE = ONE_SECOND * 60;
	public static final long ONE_HOUR = ONE_MINUTE * 60;
	public static final long ONE_DAY = ONE_HOUR * 24;
	public static final long ONE_WEEK = ONE_DAY * 7;
	public static final long ONE_YEAR = ONE_DAY * 365;
	public static final long DEFAULT_MAXIMUM_CACHED_FILE_AGE = ONE_WEEK;

	public static final int TILE_DOWNLOAD_MAXIMUM_QUEUE_SIZE = 40;
	public static final int TILE_FILESYSTEM_MAXIMUM_QUEUE_SIZE = 40;

	/** 30 days */
	public static final long TILE_EXPIRY_TIME_MILLISECONDS = 1000L * 60 * 60 * 24 * 30;

	/** default is 600 Mb */
	public static long TILE_MAX_CACHE_SIZE_BYTES = 600L * 1024 * 1024;

	/** default is 500 Mb */
	public static long TILE_TRIM_CACHE_SIZE_BYTES = 500L * 1024 * 1024;

     /** Change the root path of the osmdroid cache. 
     * By default, it is defined in SD card, osmdroid directory. 
     * @param newFullPath
     */
     public static void addCachePath(String newFullPath){
         File f=new File(newFullPath);
         if (f.exists())
               OSMDROID_PATHS.add(f);
         TILE_PATH_BASE = new File(OSMDROID_PATH, "tiles");
     }
     /**
      * all internal components now reference this call to find all cache paths
      * @since 4.4
      * @return all known default and user defined cache paths
      */
     public static List<File> getCachePaths(){
          return OSMDROID_PATHS;
     }

     /** Change the osmdroid tiles cache sizes
      * @param maxCacheSize in Mb. Default is 600 Mb. 
      * @param trimCacheSize When the cache size exceeds maxCacheSize, tiles will be automatically removed to reach this target. In Mb. Default is 500 Mb. 
      * @since 4.4
      * @author MKer
      */
     public static void setCacheSizes(long maxCacheSize, long trimCacheSize){
         TILE_MAX_CACHE_SIZE_BYTES = maxCacheSize * 1024 * 1024;
         TILE_TRIM_CACHE_SIZE_BYTES = trimCacheSize * 1024 * 1024;
     }     
}
