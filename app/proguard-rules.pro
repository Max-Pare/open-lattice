# HiveMQ MQTT client uses Netty and RxJava; keep what reflection needs and silence optional deps.
-keep class com.hivemq.client.** { *; }
-dontwarn com.hivemq.client.**
-dontwarn io.netty.**
-dontwarn org.jctools.**
-dontwarn reactor.blockhound.**
-dontwarn javax.annotation.**
