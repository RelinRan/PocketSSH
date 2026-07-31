package io.rockchip.sshsftp.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths
import java.nio.file.Files
import java.util.TreeMap

class SftpPathAliasTest {
    private val shared = Paths.get("/storage/emulated/0")
    private val shadow = Paths.get("/data/user/0/app/cache/sftp-shadow")

    @Test
    fun resolvesRemoteRootToSharedStorageWhenSshdProvidesEmptyRootDirectory() {
        assertEquals(shared, resolveRemoteSftpPath(Paths.get(""), "/", shared, shadow))
        assertEquals(shared, resolveRemoteSftpPath(Paths.get(""), ".", shared, shadow))
        assertEquals(shared.resolve("Download"), resolveRemoteSftpPath(Paths.get(""), "/Download", shared, shadow))
        assertEquals(shared, resolveRemoteSftpPath(Paths.get(""), "/..", shared, shadow))
    }

    @Test
    fun resolvesRootedSftpPathsToTheRealFilesystemRoot() {
        assertEquals(Paths.get("/"), resolveRemoteSftpPath(Paths.get("/"), "/", shared, shadow, rootAccess = true))
        assertEquals(
            Paths.get("/data/local/tmp"),
            resolveRemoteSftpPath(Paths.get("/"), "/data/local/tmp", shared, shadow, rootAccess = true)
        )
        assertEquals(
            Paths.get("/storage/emulated/0/Android"),
            resolveRemoteSftpPath(Paths.get("/"), "/storage/emulated/0/Android", shared, shadow, rootAccess = true)
        )
    }

    @Test
    fun rootedStorageAliasReturnsToFilesystemRoot() {
        assertEquals(
            Paths.get("/"),
            resolveRemoteSftpPath(Paths.get("/"), "/storage/emulated/0/..", shared, shadow, rootAccess = true)
        )
        assertEquals(
            Paths.get("/"),
            resolveRemoteSftpPath(Paths.get("/"), "/sdcard/..", shared, shadow, rootAccess = true)
        )
    }

    @Test
    fun shellNavigationUsesTheSameRootBoundaryAsSftp() {
        assertEquals(Paths.get("/"), resolveShellPath(shared, "..", shared, rootAccess = true))
        assertEquals(shared, resolveShellPath(shared, "..", shared, rootAccess = false))
        assertEquals(
            Paths.get("/data/system"),
            resolveShellPath(Paths.get("/"), "/data/system", shared, rootAccess = true)
        )
    }

    @Test
    fun doesNotDenyAndroidDirectoryWhenRooted() {
        assertFalse(isDeniedSftpPath("/storage/emulated/0/Android", rootAccess = true))
        assertFalse(isDeniedSftpPath("/storage/emulated/0/Android/data", rootAccess = true))
        assertFalse(isDeniedSftpPath("/data", rootAccess = true))
    }

    @Test
    fun deniesProtectedAndroidDirectoriesInsteadOfOpeningEmptyShadowFolders() {
        assertTrue(isDeniedSftpPath("/data"))
        assertTrue(isDeniedSftpPath("/data/system"))
        assertTrue(isDeniedSftpPath("/storage/emulated/0/Android"))
        assertTrue(isDeniedSftpPath("/storage/emulated/0/Android/data/com.example"))
        assertTrue(isDeniedSftpPath("/sdcard/Android"))
        assertFalse(isDeniedSftpPath("/storage/emulated/0/Download"))
        assertFalse(isDeniedSftpPath("/sdcard/DCIM"))
    }

    @Test
    fun mapsOnlySdcardAliasToSharedStorage() {
        assertEquals(shared, resolveSftpAlias(Paths.get("/sdcard"), shared))
        assertEquals(shared.resolve("Download/file.zip"), resolveSftpAlias(Paths.get("/sdcard/Download/file.zip"), shared))
        assertEquals(Paths.get("/storage"), resolveSftpAlias(Paths.get("/storage"), shared))
        assertEquals(Paths.get("/storage/emulated"), resolveSftpAlias(Paths.get("/storage/emulated"), shared))
    }

    @Test
    fun normalizesParentNavigationWithinSdcardAlias() {
        assertEquals(shared, resolveSftpAlias(Paths.get("/sdcard/Download/.."), shared))
        assertEquals(shared.resolve("Pictures"), resolveSftpAlias(Paths.get("/sdcard/Download/../Pictures"), shared))
        assertEquals(Paths.get("/"), resolveSftpAlias(Paths.get("/sdcard/.."), shared))
    }

    @Test
    fun resolvesSdcardFromRemotePathBeforeFollowingSystemSymlink() {
        assertEquals(shared, resolveRemoteSftpPath(Paths.get("/"), "/sdcard", shared, shadow))
        assertEquals(shared.resolve("Download"), resolveRemoteSftpPath(Paths.get("/"), "/sdcard/Download", shared, shadow))
    }

    @Test
    fun mapsRestrictedStorageParentsToAccessibleShadowDirectories() {
        assertEquals(shadow.resolve("storage"), resolveRemoteSftpPath(Paths.get("/"), "/storage", shared, shadow))
        assertEquals(shadow.resolve("storage/emulated"), resolveRemoteSftpPath(Paths.get("/"), "/storage/emulated", shared, shadow))
        assertEquals(shadow.resolve("storage/self"), resolveRemoteSftpPath(Paths.get("/"), "/storage/self", shared, shadow))
        assertEquals(shared, resolveRemoteSftpPath(Paths.get("/"), "/storage/emulated/0", shared, shadow))
        assertEquals(shared, resolveRemoteSftpPath(Paths.get("/"), "/storage/self/primary", shared, shadow))
    }

    @Test
    fun mapsAndroidDataRestrictedPathsToRootBackedShadowPaths() {
        assertEquals(
            shadow.resolve("root/storage/emulated/0/Android"),
            resolveRemoteSftpPath(Paths.get("/"), "/storage/emulated/0/Android", shared, shadow)
        )
        assertEquals(
            shadow.resolve("root/storage/emulated/0/Android/data"),
            resolveRemoteSftpPath(Paths.get("/"), "/storage/emulated/0/Android/data", shared, shadow)
        )
        assertEquals(
            shadow.resolve("root/storage/emulated/0/Android/data/com.example/files/a.txt"),
            resolveRemoteSftpPath(Paths.get("/"), "/sdcard/Android/data/com.example/files/a.txt", shared, shadow)
        )
        assertEquals(
            "/storage/emulated/0/Android/data/com.example/files/a.txt",
            resolveRootBackedSftpPath(shadow.resolve("root/storage/emulated/0/Android/data/com.example/files/a.txt"), shadow)
        )
    }

    @Test
    fun mapsDataRestrictedPathsToRootBackedShadowPaths() {
        assertEquals(
            shadow.resolve("root/data"),
            resolveRemoteSftpPath(Paths.get("/"), "/data", shared, shadow)
        )
        assertEquals(
            shadow.resolve("root/data/system"),
            resolveRemoteSftpPath(Paths.get("/"), "/data/system", shared, shadow)
        )
        assertEquals(
            "/data/system/packages.list",
            resolveRootBackedSftpPath(shadow.resolve("root/data/system/packages.list"), shadow)
        )
    }

    @Test
    fun mapsAndroidRootToRootBackedShadowPath() {
        assertEquals(
            shadow.resolve("root/storage/emulated/0/Android"),
            resolveRemoteSftpPath(Paths.get("/"), "/storage/emulated/0/Android", shared, shadow)
        )
        assertEquals(
            shadow.resolve("root/storage/emulated/0/Android/data"),
            resolveRemoteSftpPath(Paths.get("/"), "/storage/emulated/0/Android/data", shared, shadow)
        )
    }

    @Test
    fun keepsRootBackedDirectoryEntriesOnShadowTree() {
        val dataDir = resolveRemoteSftpPath(Paths.get("/"), "/data", shared, shadow)
        val androidDir = resolveRemoteSftpPath(Paths.get("/"), "/storage/emulated/0/Android", shared, shadow)
        assertTrue(dataDir.toString().contains("sftp-shadow"))
        assertTrue(androidDir.toString().contains("sftp-shadow"))
    }

    @Test
    fun doesNotRemapAlreadyShadowResolvedPaths() {
        val alreadyShadow = shadow.resolve("root/data")
        assertEquals(
            alreadyShadow,
            resolveRemoteSftpPath(alreadyShadow, alreadyShadow.toString(), shared, shadow)
        )
    }

    @Test
    fun createsShadowStorageTreeForBrowsableStorageParents() {
        val root = Files.createTempDirectory("pocketssh-sftp-shadow")

        ensureSftpShadowTree(root)

        assertTrue(Files.isDirectory(root.resolve("storage")))
        assertTrue(Files.isDirectory(root.resolve("virtual-root/storage")))
        assertTrue(Files.isDirectory(root.resolve("virtual-root/sdcard")))
        assertTrue(Files.isDirectory(root.resolve("storage/emulated")))
        assertTrue(Files.isDirectory(root.resolve("storage/emulated/0")))
        assertTrue(Files.isDirectory(root.resolve("storage/self")))
        assertTrue(Files.isDirectory(root.resolve("storage/self/primary")))
        assertTrue(Files.isDirectory(root.resolve("root/data")))
        assertTrue(Files.isDirectory(root.resolve("root/storage/emulated/0/Android")))
        assertTrue(Files.isDirectory(root.resolve("root/storage/emulated/0/Android/data")))
        assertTrue(Files.isDirectory(root.resolve("root/storage/emulated/0/Android/obb")))
    }

    @Test
    fun reportsSdcardAliasAsDirectoryInsteadOfSymbolicLink() {
        val attributes = sftpSdcardDirectoryAttributes(123L)

        assertTrue(attributes["isDirectory"] == true)
        assertFalse(attributes["isSymbolicLink"] == true)
        assertEquals(0L, attributes["size"])
    }

    @Test
    fun normalizesAccessibleSymbolicLinksToTheirTargetType() {
        val link = TreeMap<String, Any>().apply {
            putAll(mapOf(
            "isDirectory" to false,
            "isRegularFile" to false,
            "isSymbolicLink" to true,
            ))
        }
        val directory = mapOf<String, Any>("isDirectory" to true, "isRegularFile" to false, "size" to 0L)
        val file = mapOf<String, Any>("isDirectory" to false, "isRegularFile" to true, "size" to 128L)

        val directoryResult = normalizeSftpSymbolicLinkAttributes(link, directory)
        val fileResult = normalizeSftpSymbolicLinkAttributes(link, file)
        val brokenResult = normalizeSftpSymbolicLinkAttributes(link, null)

        assertTrue(directoryResult["isDirectory"] == true)
        assertFalse(directoryResult["isSymbolicLink"] == true)
        assertTrue(fileResult["isRegularFile"] == true)
        assertEquals(128L, fileResult["size"])
        assertTrue(brokenResult["isSymbolicLink"] == true)
    }

    @Test
    fun keepsRootAndCanonicalSharedStoragePathsUnchanged() {
        assertEquals(Paths.get("/"), resolveSftpAlias(Paths.get("/"), shared))
        assertEquals(shared, resolveSftpAlias(shared, shared))
        assertEquals(shared.resolve("Pictures"), resolveSftpAlias(shared.resolve("Pictures"), shared))
    }
}
