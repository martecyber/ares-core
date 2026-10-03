package com.martecyber.ares.storage;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.EnumSet;

@Service
@ConditionalOnProperty(name = "ares.storage.type", havingValue = "smb")
public class SmbStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(SmbStorageService.class);

    @Value("${ares.storage.smb.host}")
    private String host;

    @Value("${ares.storage.smb.port:445}")
    private int port;

    @Value("${ares.storage.smb.share}")
    private String shareName;

    @Value("${ares.storage.smb.domain:}")
    private String domain;

    @Value("${ares.storage.smb.username}")
    private String username;

    @Value("${ares.storage.smb.password}")
    private String password;

    @Value("${ares.storage.smb.base-path:}")
    private String basePath;

    private final SMBClient client = new SMBClient();

    @Override
    public void ensureBucketExists(String bucket) {
        try {
            execute(share -> {
                mkdirs(share, bucketDir(bucket));
                return null;
            });
        } catch (IOException e) {
            log.warn("Could not create SMB storage directory for bucket '{}': {}", bucket, e.getMessage());
        }
    }

    @Override
    public boolean exists(String bucket, String key) {
        try {
            return execute(share -> share.fileExists(objectPath(bucket, key)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void put(String bucket, String key, String contentType, byte[] content) {
        try {
            execute(share -> {
                String path = objectPath(bucket, key);
                mkdirs(share, parentDir(path));
                try (File file = share.openFile(
                        path,
                        EnumSet.of(AccessMask.GENERIC_WRITE),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
                        SMB2CreateDisposition.FILE_OVERWRITE_IF,
                        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE))) {
                    try (OutputStream os = file.getOutputStream()) {
                        os.write(content);
                    }
                }
                return null;
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] get(String bucket, String key) {
        try {
            return execute(share -> {
                String path = objectPath(bucket, key);
                try (File file = share.openFile(
                        path,
                        EnumSet.of(AccessMask.GENERIC_READ),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.noneOf(SMB2CreateOptions.class))) {
                    try (InputStream is = file.getInputStream()) {
                        return is.readAllBytes();
                    }
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String bucket, String key) {
        try {
            execute(share -> {
                String path = objectPath(bucket, key);
                if (share.fileExists(path)) {
                    share.rm(path);
                }
                return null;
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private interface ShareAction<T> {
        T run(DiskShare share) throws IOException;
    }

    private <T> T execute(ShareAction<T> action) throws IOException {
        try (Connection connection = client.connect(host, port)) {
            AuthenticationContext ac = new AuthenticationContext(username, password.toCharArray(), domain.isBlank() ? null : domain);
            try (Session session = connection.authenticate(ac)) {
                try (DiskShare share = (DiskShare) session.connectShare(shareName)) {
                    return action.run(share);
                }
            }
        }
    }

    // Object keys are always server-generated (UUIDs, timestamps, ids) at every call site, never
    // raw user input, mirroring LocalFilesystemStorageService's own convention.
    private void mkdirs(DiskShare share, String dirPath) {
        if (dirPath == null || dirPath.isEmpty()) {
            return;
        }
        StringBuilder current = new StringBuilder();
        for (String part : dirPath.split("\\\\")) {
            if (part.isEmpty()) {
                continue;
            }
            if (current.length() > 0) {
                current.append("\\");
            }
            current.append(part);
            if (!share.folderExists(current.toString())) {
                share.mkdir(current.toString());
            }
        }
    }

    private String bucketDir(String bucket) {
        String base = stripSlashes(basePath);
        String b = stripSlashes(bucket);
        return base.isEmpty() ? b : base + "\\" + b;
    }

    private String objectPath(String bucket, String key) {
        String dir = bucketDir(bucket);
        String k = key.replace('/', '\\');
        return dir.isEmpty() ? k : dir + "\\" + k;
    }

    private String parentDir(String path) {
        int idx = path.lastIndexOf('\\');
        return idx < 0 ? "" : path.substring(0, idx);
    }

    private String stripSlashes(String s) {
        if (s == null) {
            return "";
        }
        String result = s.replace('/', '\\');
        while (result.startsWith("\\")) {
            result = result.substring(1);
        }
        while (result.endsWith("\\")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
