package org.osmdroid.tileprovider.modules;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import org.osmdroid.api.IMapView;
import org.osmdroid.tileprovider.MapTile;
import org.osmdroid.tileprovider.constants.OpenStreetMapTileProviderConstants;
import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.util.Counters;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.osmdroid.tileprovider.modules.DatabaseFileArchive.TABLE;

/**
 * An implementation of {@link IFilesystemCache} based on the original TileWriter. It writes tiles to a sqlite database cache.
 * It supports expiration timestamps if provided by the server from which the tile was downloaded. Trimming
 * of expired
 * <p>
 * If the database exceeds {@link OpenStreetMapTileProviderConstants#TILE_TRIM_CACHE_SIZE_BYTES}
 * cache exceeds 600 Mb then it will be trimmed to 500 Mb by deleting files that expire first.
 *
 * @author Alex O'Ree
 * @since 5.1
 */
public class SqlTileWriter implements IFilesystemCache {
    protected File db_file;
    protected SQLiteDatabase db;
    final int questimate=8000;
    static boolean hasInited=false;

    public SqlTileWriter() {

        db_file = new File(OpenStreetMapTileProviderConstants.TILE_PATH_BASE.getAbsolutePath() + File.separator + "cache.db");
        OpenStreetMapTileProviderConstants.TILE_PATH_BASE.mkdirs();

        try {
            db = SQLiteDatabase.openOrCreateDatabase(db_file, null);
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE+ " ("+DatabaseFileArchive.COLUMN_KEY+" INTEGER , "+DatabaseFileArchive.COLUMN_PROVIDER +" TEXT, "+DatabaseFileArchive.COLUMN_TILE+" BLOB, expires INTEGER, PRIMARY KEY ("+DatabaseFileArchive.COLUMN_KEY+", "+DatabaseFileArchive.COLUMN_PROVIDER+"));");
        }
        catch (Throwable ex){
            Log.e(IMapView.LOGTAG, "Unable to start the sqlite tile writer. Check external storage availability.", ex);
        }
        if (!hasInited){
            hasInited=true;

            // do this in the background because it takes a long time
            final Thread t = new Thread() {
                @Override
                public void run() {
                    if (db==null) {
                        if (OpenStreetMapTileProviderConstants.DEBUGMODE) {
                            Log.d(IMapView.LOGTAG, "Finished init thread, aborted due to null database reference");
                        }
                        return;
                    }

                    try {
                        //run the reaper (remove all old expired tiles)
                        //keep if now is < expiration date
                        //delete if now is > expiration date
                        long now = System.currentTimeMillis();
                        int rows = db.delete(TABLE, "expires < ?", new String[]{System.currentTimeMillis() + ""});
                        Log.d(IMapView.LOGTAG, "Local storage cahce purged " + rows + " expired tiles in " + (System.currentTimeMillis() - now) + "ms, cache size is " + db_file.length() + "bytes");

                        //VACUUM the database
                        now = System.currentTimeMillis();
                        //db.execSQL("VACUUM " + DatabaseFileArchive.TABLE + ";");
                        // Log.d(IMapView.LOGTAG, "VACUUM completed in " + (System.currentTimeMillis()-now) + "ms, cache size is " + db_file.length() + "bytes");
                        if (db_file.length() > OpenStreetMapTileProviderConstants.TILE_MAX_CACHE_SIZE_BYTES) {
                            long diff = OpenStreetMapTileProviderConstants.TILE_MAX_CACHE_SIZE_BYTES - db_file.length();
                            long tilesToKill = diff / questimate;
                            try {
                                db.execSQL("DELETE FROM " + TABLE + " ORDER BY expires DESC LIMIT " + tilesToKill);
                            } catch (Throwable t) {
                                t.printStackTrace();
                            }
                            Log.d(IMapView.LOGTAG, "purge completed in " + (System.currentTimeMillis() - now) + "ms, cache size is " + db_file.length() + "bytes");
                        }
                    }catch (Exception ex){
                        if (OpenStreetMapTileProviderConstants.DEBUGMODE) {
                            Log.d(IMapView.LOGTAG, "SqliteTileWriter init thread crash, db is probably not available",ex);
                        }
                    }

                    if (OpenStreetMapTileProviderConstants.DEBUGMODE) {
                        Log.d(IMapView.LOGTAG, "Finished init thread");
                    }
                }
            };
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        }
    }

    @Override
    public boolean saveFile(ITileSource pTileSourceInfo, MapTile pTile, InputStream pStream) {
        if (db == null) {
            Log.d(IMapView.LOGTAG, "Unable to store cached tile from " + pTileSourceInfo.name() + " " + pTile.toString() + ", database not available.");
            Counters.fileCacheSaveErrors++;
            return false;
        }
        try {
            ContentValues cv = new ContentValues();
            final long x = (long) pTile.getX();
            final long y = (long) pTile.getY();
            final long z = (long) pTile.getZoomLevel();
            final long index = ((z << z) + x << z) + y;
            cv.put(DatabaseFileArchive.COLUMN_PROVIDER, pTileSourceInfo.name());
            BufferedInputStream bis = new BufferedInputStream(pStream);

            List<Byte> list = new ArrayList<Byte>();
            //ByteArrayBuffer baf = new ByteArrayBuffer(500);
            int current = 0;
            while ((current = bis.read()) != -1) {
                list.add((byte) current);
            }

            byte[] bits = new byte[list.size()];
            for (int i = 0; i < list.size(); i++) bits[i] = list.get(i);
            cv.put(DatabaseFileArchive.COLUMN_KEY, index);
            cv.put(DatabaseFileArchive.COLUMN_TILE, bits);
            //this shouldn't happen, but just in case
            if (pTile.getExpires() != null)
                cv.put("expires", pTile.getExpires().getTime());
            db.delete(TABLE, DatabaseFileArchive.COLUMN_KEY+"=? and "+DatabaseFileArchive.COLUMN_PROVIDER+"=?", new String[]{index+"",pTileSourceInfo.name()});
            db.insert(TABLE, null, cv);
            Log.d(IMapView.LOGTAG, "tile inserted " + pTileSourceInfo.name() +  pTile.toString());
        } catch (Throwable ex) {
            //note, although we check for db null state at the beginning of this method, it's possible for the
            //db to be closed during the execution of this method
            Log.e(IMapView.LOGTAG, "Unable to store cached tile from " + pTileSourceInfo.name() + " " + pTile.toString() + " db is " +(db==null ? "null":"not null"), ex);
            Counters.fileCacheSaveErrors++;
        }
        return false;
    }

    @Override
    public boolean exists(ITileSource pTileSource, MapTile pTile) {
        try {
            final String[] tile = {DatabaseFileArchive.COLUMN_TILE};
            final long x = (long) pTile.getX();
            final long y = (long) pTile.getY();
            final long z = (long) pTile.getZoomLevel();
            final long index = ((z << z) + x << z) + y;
            final Cursor cur = db.query(TABLE, tile, DatabaseFileArchive.COLUMN_KEY+" = " + index + " and "+DatabaseFileArchive.COLUMN_PROVIDER+" = '" + pTileSource.name() + "'", null, null, null, null);

            if(cur.getCount() != 0) {
                cur.close();
                return true;
            }
            cur.close();
        } catch (Throwable ex) {
            Log.e(IMapView.LOGTAG, "Unable to store cached tile from " + pTileSource.name() + " " + pTile.toString(), ex);
        }
        return false;
    }

    @Override
    public void onDetach() {
        if (db != null && db.isOpen()) {
            try {
                db.close();
            } catch (Exception ex) {
            }
        }
        db = null;
        db_file = null;
    }

    /**
     * purges and deletes everything from the cache database
     * @return
     */
    public boolean purgeCache() {
        if (db!=null && db.isOpen()){
            try {
                db.delete(TABLE, null, null);
                return true;
            } catch(final Throwable e) {
                Log.w(IMapView.LOGTAG,"Error purging the db", e);
            }
        }
        return false;
    }

    /**
     * a helper method to import file system stored map tiles into the sql tile cache
     * on successful import, the tiles are removed from the file system
     * @return
     */
    public int[] importFromFileCache(boolean removeFromFileSystem) {
        int[] ret = new int[] {0,0,0,0};
        //inserts
        //insert failures
        //deletes
        //delete failures
        File tilePathBase = OpenStreetMapTileProviderConstants.TILE_PATH_BASE;
        if (tilePathBase.exists()){
            File[] tileSources = tilePathBase.listFiles();
            if (tileSources!=null){
                for (int i=0; i<tileSources.length; i++){
                    if (tileSources[i].isDirectory() && !tileSources[i].isHidden() && tileSources[i].isFile()){
                        //proceed
                        File[] z = tileSources[i].listFiles();
                        if (z!=null)
                            for (int zz=0; zz<z.length; zz++){
                                if (z[zz].isDirectory() && !z[zz].isHidden() && z[zz].isFile()){
                                    File[] x = z[zz].listFiles();
                                    if (x!=null)
                                        for (int xx=0; xx<x.length; xx++){
                                            if (x[xx].isDirectory() && !x[xx].isHidden() && x[xx].isFile()){
                                                File[] y = x[xx].listFiles();
                                                if (x!=null)
                                                    for (int yy=0; yy<y.length; yy++){
                                                        if (!y[yy].isHidden() && y[yy].isFile() && !y[yy].isDirectory()){

                                                            try {
                                                                ContentValues cv = new ContentValues();
                                                                final long x1 = Long.parseLong(x[xx].getName());
                                                                final long y1 = Long.parseLong(y[yy].getName());
                                                                final long z1 = Long.parseLong(z[zz].getName());
                                                                final long index = ((z1 << z1) + x1 << z1) + y1;
                                                                cv.put(DatabaseFileArchive.COLUMN_PROVIDER, tileSources[i].getName());
                                                                BufferedInputStream bis = new BufferedInputStream(new FileInputStream(y[yy]));

                                                                List<Byte> list = new ArrayList<Byte>();
                                                                //ByteArrayBuffer baf = new ByteArrayBuffer(500);
                                                                int current = 0;
                                                                while ((current = bis.read()) != -1) {
                                                                    list.add((byte) current);
                                                                }

                                                                byte[] bits = new byte[list.size()];
                                                                for (int bi = 0; bi < list.size(); bi++) bits[bi] = list.get(bi);
                                                                cv.put(DatabaseFileArchive.COLUMN_KEY, index);
                                                                cv.put(DatabaseFileArchive.COLUMN_TILE, bits);

                                                                db.insert(TABLE, null, cv);
                                                                Log.d(IMapView.LOGTAG, "tile inserted " + tileSources[i].getName()+"/"+  z1 + "/" + x1 + "/" + y1);
                                                                ret[0]++;
                                                                if (removeFromFileSystem){
                                                                    try {
                                                                        y[yy].delete();
                                                                        ret[2]++;;
                                                                    }catch (Exception ex){
                                                                        ret[3]++;;
                                                                    }
                                                                }
                                                            } catch (Throwable ex) {
                                                                //note, although we check for db null state at the beginning of this method, it's possible for the
                                                                //db to be closed during the execution of this method
                                                                Log.e(IMapView.LOGTAG, "Unable to store cached tile from " + tileSources[i].getName() + " db is " +(db==null ? "null":"not null"), ex);
                                                                ret[1]++;
                                                            }
                                                        }
                                                    }
                                            }
                                        }
                                }
                            }



                    } else {
                        //it's a file, nothing for us to do here
                    }
                }
            }

        }
        return ret;
    }


}
