package cc.cherr.codexdeck

import android.app.Application
import androidx.room.Room
import cc.cherr.codexdeck.data.CodexDeckDatabase
import cc.cherr.codexdeck.data.CredentialVault

class CodexDeckApplication : Application() {
    val runtime: AppController by lazy {
        val database = Room.databaseBuilder(
            applicationContext,
            CodexDeckDatabase::class.java,
            "codexdeck.db",
        ).build()
        AppController(
            context = applicationContext,
            dao = database.records(),
            vault = CredentialVault(),
        )
    }
}
