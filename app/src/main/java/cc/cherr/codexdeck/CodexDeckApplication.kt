package cc.cherr.codexdeck

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cc.cherr.codexdeck.data.CodexDeckDatabase
import cc.cherr.codexdeck.data.CredentialVault

class CodexDeckApplication : Application() {
    val runtime: AppController by lazy {
        val database = Room.databaseBuilder(
            applicationContext,
            CodexDeckDatabase::class.java,
            "codexdeck.db",
        ).addMigrations(MIGRATION_1_2).build()
        AppController(
            context = applicationContext,
            dao = database.records(),
            vault = CredentialVault(),
        )
    }

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE hosts ADD COLUMN appServerTransport TEXT NOT NULL DEFAULT 'ssh'",
                )
                db.execSQL(
                    "ALTER TABLE hosts ADD COLUMN appServerUrl TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE hosts ADD COLUMN encryptedAppServerToken BLOB NOT NULL DEFAULT X''",
                )
                db.execSQL(
                    "ALTER TABLE hosts ADD COLUMN webSocketViaSsh INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE hosts ADD COLUMN allowInsecureWebSocket INTEGER NOT NULL DEFAULT 0",
                )
            }
        }
    }
}
