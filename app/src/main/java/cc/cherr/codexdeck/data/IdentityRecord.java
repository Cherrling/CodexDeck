package cc.cherr.codexdeck.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "identities")
public class IdentityRecord {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public String label = "";
    @NonNull public String algorithm = "";
    @NonNull public String fingerprint = "";
    @Nullable public String publicKey;
    @NonNull public byte[] encryptedPrivateKey = new byte[0];
    @NonNull public long createdAt = 0;
}
