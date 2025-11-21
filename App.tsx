import React, { useState } from 'react';
import { Button, View, Alert, Platform, StyleSheet, Text, ActivityIndicator } from 'react-native';
import { NativeModules } from 'react-native';
import RNFS from 'react-native-fs';

const { PdfModule } = NativeModules;

export default function App() {
  const [downloading, setDownloading] = useState(false);
  const [downloadProgress, setDownloadProgress] = useState(0);

  // Open PDF from online URL or local file URI
  const openPdfViewer = async () => {
    try {
      // Online PDF URL (62 pages)
      const pdfUrl = "https://os.ecci.ucr.ac.cr/slides/Abraham-Silberschatz-Operating-System-Concepts-10th-2018.pdf";

      setDownloading(true);
      setDownloadProgress(0);

      // Download to temporary cache directory
      const localPath = `${RNFS.CachesDirectoryPath}/pdf_${Date.now()}.pdf`;

      const download = await RNFS.downloadFile({
        fromUrl: pdfUrl,
        toFile: localPath,
        // Track download progress
        progress: (res) => {
          const progress = res.bytesWritten / res.contentLength;
          setDownloadProgress(Math.round(progress * 100));
        },
        // Download in background
        background: true,
        progressDivider: 10, // Update progress every 10%
      }).promise;

      if (download.statusCode === 200) {
        setDownloading(false);
        
        const fileUri =
          Platform.OS === "android" ? "file://" + localPath : localPath;

        // Open in native PDF viewer with chunked loading
        PdfModule.openPdfInNativeViewer(fileUri);
      } else {
        setDownloading(false);
        Alert.alert("Error", "Failed to download PDF");
      }
    } catch (error) {
      console.error(error);
      setDownloading(false);
      Alert.alert("Error", String(error));
    }
  };

  return (
    <View style={styles.container}>
      {downloading ? (
        <View style={styles.downloadContainer}>
          <ActivityIndicator size="large" color="#007AFF" />
          <Text style={styles.downloadText}>
            Downloading PDF... {downloadProgress}%
          </Text>
          <Text style={styles.infoText}>
            Large PDFs load efficiently in chunks
          </Text>
        </View>
      ) : (
        <Button
          title="Open PDF Viewer"
          onPress={openPdfViewer}
          color="#007AFF"
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 20,
    justifyContent: 'center',
  },
  downloadContainer: {
    alignItems: 'center',
    justifyContent: 'center',
  },
  downloadText: {
    marginTop: 16,
    fontSize: 16,
    fontWeight: '600',
    color: '#333',
  },
  infoText: {
    marginTop: 8,
    fontSize: 12,
    color: '#666',
    textAlign: 'center',
  },
  spacer: {
    height: 12,
  },
});

