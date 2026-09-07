import 'package:flutter/material.dart';
import 'screens/dashboard_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const BinanceTestnetTraderApp());
}

class BinanceTestnetTraderApp extends StatelessWidget {
  const BinanceTestnetTraderApp({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Binance Testnet Trader',
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark().copyWith(
        scaffoldBackgroundColor: const Color(0xFF0A1628),
        colorScheme: const ColorScheme.dark(
          primary: Color(0xFF00D4FF),
          secondary: Color(0xFFFF8A65),
          surface: Color(0xFF0D2340),
        ),
      ),
      home: const DashboardScreen(),
    );
  }
}
