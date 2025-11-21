import React from 'react';
import { Button, View, Alert, Platform, StyleSheet } from 'react-native';
import { NativeModules } from 'react-native';
import RNFS from 'react-native-fs';

const { PdfModule } = NativeModules;

export default function App() {
  // Open PDF from online URL or local file URI
  const openPdfViewer = async () => {
    try {
      // Online PDF URL (62 pages)
      const pdfUrl = "https://www.eci.gov.in/eci-backend/public/api/download?url=LMAhAK6sOPBp%2FNFF0iRfXbEB1EVSLT41NNLRjYNJJP1KivrUxbfqkDatmHy12e%2FzVx8fLfn2ReU7TfrqYobgIvZ0n19tFMJzYNUMlQdr0LSqdxInuthjtzMEf7FQu2OqdtjQjVSaWEoXcHWTWtuQ2UVk4VB4Sl65%2BgZZOXFvTbY%3D";

      Alert.alert("Downloading PDF", "Please wait...");

      // Download to temporary cache directory
      const localPath = `${RNFS.CachesDirectoryPath}/pdf_${Date.now()}.pdf`;

      const download = await RNFS.downloadFile({
        fromUrl: pdfUrl,
        toFile: localPath,
      }).promise;

      if (download.statusCode === 200) {
        const fileUri =
          Platform.OS === "android" ? "file://" + localPath : localPath;

        // Open in native PDF viewer
        PdfModule.openPdfInNativeViewer(fileUri);
      } else {
        Alert.alert("Error", "Failed to download PDF");
      }
    } catch (error) {
      console.error(error);
      Alert.alert("Error", String(error));
    }
  };

  return (
    <View style={styles.container}>
      <Button
        title="Open PDF Viewer"
        onPress={openPdfViewer}
        color="#007AFF"
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 20,
    justifyContent: 'center',
  },
  spacer: {
    height: 12,
  },
});

