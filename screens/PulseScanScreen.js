// screens/PulseScanScreen.js
import React, { useEffect, useState } from 'react';
import { View, Text, Button, StyleSheet, NativeModules, NativeEventEmitter, Alert } from 'react-native';
import { Camera } from 'expo-camera';

const { PpgModule } = NativeModules;
const emitter = PpgModule ? new NativeEventEmitter(PpgModule) : null;

export default function PulseScanScreen() {
  const [hasPermission, setHasPermission] = useState(null);
  const [status, setStatus] = useState('Ready');
  const [progress, setProgress] = useState(0);
  const [bpm, setBpm] = useState(null);
  const [scanning, setScanning] = useState(false);

  useEffect(() => {
    (async () => {
      const { status } = await Camera.requestCameraPermissionsAsync();
      setHasPermission(status === 'granted');
      if (status !== 'granted') Alert.alert('Permission required', 'Camera permission is required to measure pulse.');
    })();
  }, []);

  useEffect(() => {
    if (!emitter) return;
    const s1 = emitter.addListener('onStarted', () => {
      setStatus('Scanning — keep finger still and fully cover camera + flash');
      setScanning(true);
      setBpm(null);
    });
    const s2 = emitter.addListener('onProgress', (p) => {
      setProgress(parseInt(p, 10) || 0);
      setStatus(`Scanning ${p}%`);
    });
    const s3 = emitter.addListener('onResult', (val) => {
      setBpm(parseFloat(val));
      setStatus('Done');
      setScanning(false);
      setProgress(100);
    });
    const s4 = emitter.addListener('onError', (err) => {
      setStatus('Error: ' + err);
      setScanning(false);
      Alert.alert('Measurement error', `${err}. Try again: stay still, fully cover camera with fingertip, moderate pressure.`);
    });

    return () => { s1.remove(); s2.remove(); s3.remove(); s4.remove(); };
  }, []);

  const start = async () => {
    if (!hasPermission) {
      const { status } = await Camera.requestCameraPermissionsAsync();
      setHasPermission(status === 'granted');
      if (status !== 'granted') return;
    }
    if (!PpgModule || !PpgModule.startScan) {
      Alert.alert('Native module missing', 'PpgModule native module not found. Rebuild the app with native code.');
      return;
    }
    setStatus('Preparing camera...');
    setProgress(0);
    setBpm(null);
    PpgModule.startScan(30);
  };

  const stop = () => {
    if (PpgModule && PpgModule.stopScan) PpgModule.stopScan();
  };

  return (
    <View style={styles.container}>
      <Text style={styles.title}>PPG Pulse (Camera)</Text>
      <Text style={styles.instructions}>Place the pad of your finger firmly and fully over the rear camera & flash. Keep still.</Text>
      <Text style={styles.status}>{status}</Text>
      <View style={styles.progressBox}><View style={[styles.progressBar, { width: `${progress}%` }]} /></View>
      {bpm !== null && <Text style={styles.bpm}>BPM: {bpm.toFixed(1)}</Text>}
      <View style={styles.buttons}>
        <Button title="Start (30s)" onPress={start} disabled={scanning} />
        <View style={{ height: 8 }} />
        <Button title="Stop" onPress={stop} disabled={!scanning} />
      </View>
      <Text style={styles.note}>Not medical. For demonstration only.</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex:1, padding:20, alignItems:'center', justifyContent:'flex-start' },
  title: { fontSize:22, fontWeight:'700', marginTop:10 },
  instructions: { textAlign:'center', marginVertical:8 },
  status: { marginVertical:6 },
  progressBox: { width:'90%', height:12, backgroundColor:'#eee', borderRadius:6, overflow:'hidden', marginTop:10 },
  progressBar: { height:'100%', backgroundColor:'#3b82f6' },
  bpm: { fontSize:36, fontWeight:'700', marginTop:12 },
  buttons: { marginTop:22, width:'80%' },
  note: { marginTop:20, fontSize:12, color:'#666', textAlign:'center' }
});
