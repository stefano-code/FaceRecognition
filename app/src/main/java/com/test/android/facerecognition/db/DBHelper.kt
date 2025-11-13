package com.test.android.facerecognition.db

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.test.android.facerecognition.face_recognition.FaceClassifier


const val DATABASE_NAME = "MyFaces.db"
const val DATABASE_VERSION = 1
const val FACE_TABLE_NAME = "faces"
const val FACE_COLUMN_ID = "id"
const val FACE_COLUMN_NAME = "name"
const val FACE_COLUMN_EMBEDDING = "embedding"

class DBHelper(ctx: Context) : SQLiteOpenHelper(ctx, DATABASE_NAME , null, DATABASE_VERSION) {


    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("create table $FACE_TABLE_NAME ($FACE_COLUMN_ID integer primary key, $FACE_COLUMN_NAME text,$FACE_COLUMN_EMBEDDING text)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS faces");
        onCreate(db)
    }

    fun insertFace(name: String?, embedding: Any): Boolean {
        val floatList = embedding as Array<FloatArray>
        var embeddingString = ""
        for (f in floatList[0]) {
            embeddingString += "$f,"
        }
        val contentValues = ContentValues()
        contentValues.put(FACE_COLUMN_NAME, name)
        contentValues.put(FACE_COLUMN_EMBEDDING, embeddingString)
        writableDatabase.insert("faces", null, contentValues)
        return true
    }

    fun getData(id: Int): Cursor? {
        return readableDatabase.rawQuery("select * from $FACE_TABLE_NAME where $FACE_COLUMN_ID=$id", null)
    }

    fun numberOfRows(): Int {
        return DatabaseUtils.queryNumEntries(readableDatabase, FACE_TABLE_NAME).toInt()
    }

    fun updateFace(id: Int?, name: String?, embedding: String?): Boolean {
        val db = this.writableDatabase
        val contentValues = ContentValues()
        contentValues.put(FACE_COLUMN_NAME, name)
        contentValues.put(FACE_COLUMN_EMBEDDING, embedding)
        db.update(FACE_TABLE_NAME, contentValues, "id = ? ", arrayOf(Integer.toString(id!!)))
        return true
    }

    fun deleteFace(id: Int?): Int? {
        return writableDatabase.delete(FACE_TABLE_NAME, "id = ? ", arrayOf(Integer.toString(id!!)))
    }

    @SuppressLint("Range")
    fun getAllFaces(): HashMap<String, FaceClassifier.Recognition>? {
        val res = readableDatabase.rawQuery("select * from $FACE_TABLE_NAME", null)
        res.moveToFirst()
        val registered = HashMap<String, FaceClassifier.Recognition>()
        while (res.isAfterLast == false) {
            val embeddingString = res.getString(res.getColumnIndex(FACE_COLUMN_EMBEDDING))
            val stringList = embeddingString.split(",".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()
            val embeddingFloat = ArrayList<Float>()
            for (s in stringList) {
                embeddingFloat.add(s.toFloat())
            }
            val bigArray = Array(1) { FloatArray(1)}
            val floatArray = FloatArray(embeddingFloat.size)
            for (i in embeddingFloat.indices) {
                floatArray[i] = embeddingFloat[i]
            }
            bigArray[0] = floatArray
            embeddingFloat.removeAt(embeddingFloat.size - 1)
            val recognition = FaceClassifier.Recognition(
                res.getString(res.getColumnIndex(FACE_COLUMN_NAME)),
                bigArray
            )
            registered.putIfAbsent(recognition.title, recognition)
            res.moveToNext()
        }
        Log.d("tryRL", "rl=" + registered.size)
        return registered
    }
}