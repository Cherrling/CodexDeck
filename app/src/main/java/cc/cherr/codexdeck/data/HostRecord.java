package cc.cherr.codexdeck.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
    tableName = "hosts",
    foreignKeys = @ForeignKey(
        entity = IdentityRecord.class,
        parentColumns = "id",
        childColumns = "identityId",
        onDelete = ForeignKey.RESTRICT
    ),
    indices = {@Index("identityId"), @Index("jumpHostId")}
)
public class HostRecord {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public String label = "";
    @NonNull public String groupName = "";
    @NonNull public String tags = "";
    @NonNull public String hostname = "";
    public int port = 22;
    @NonNull public String username = "";
    @NonNull public String authType = "key";
    @Nullable public String identityId;
    @NonNull public byte[] encryptedPassword = new byte[0];
    @Nullable public String jumpHostId;
    @NonNull public String codexExecutable = "codex";
    public int connectTimeoutMs = 15000;
    public int keepAliveSeconds = 60;
    public int retryCount = 1;
    @ColumnInfo(defaultValue = "0") public boolean favorite = false;
    @ColumnInfo(defaultValue = "0") public long lastUsedAt = 0;
    @ColumnInfo(defaultValue = "0") public long createdAt = 0;
    @ColumnInfo(defaultValue = "0") public long updatedAt = 0;
}
