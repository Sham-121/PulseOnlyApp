import React from 'react';
import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import PulseScanScreen from './screens/PulseScanScreen';

const Stack = createNativeStackNavigator();

export default function App() {
  return (
    <NavigationContainer>
      <Stack.Navigator>
        <Stack.Screen name="PulseScan" component={PulseScanScreen} options={{ title: 'PPG Pulse' }} />
      </Stack.Navigator>
    </NavigationContainer>
  );
}
