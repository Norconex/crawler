#!/bin/sh

cd $(dirname $0)
export ROOT_DIR=$(realpath $(dirname $0))

# Third-party libraries sometimes have to create and write to temporary files.
# By default those are created in your system "temp" folder
# (usually /tmp/ under Linux/Unix).
# To change the temporary location those libraries will use, add the
# following to the java command below (replacing the path):
#
#     -Djava.io.tmpdir=/path/to/tmp

# If you are experiencing memory problems or simply wish to increase crawling
# performance you can specify the amount of memory allocated by increasing
# the Java heap space. You can do so by adding the following to the Java
# command below (using 2G as an example):  
#
#     -Xmx2G

# For advanced users, JMX monitoring can be enabled by adding the following 
# to the java command below: 
#
#     -DenableJMX=true


java -Dlog4j2.configurationFile="file:${ROOT_DIR}/log4j2.xml" \
-Dfile.encoding=UTF8 \
-Xms512m \
-Xmx512m \
-Djava.net.preferIPv4Stack=true \
--add-opens=java.base/jdk.internal.access=ALL-UNNAMED \
--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
--add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
--add-opens=java.base/sun.util.calendar=ALL-UNNAMED \
--add-opens=java.management/com.sun.jmx.mbeanserver=ALL-UNNAMED \
--add-opens=jdk.internal.jvmstat/sun.jvmstat.monitor=ALL-UNNAMED \
--add-opens=java.base/sun.reflect.generics.reflectiveObjects=ALL-UNNAMED \
--add-opens=jdk.management/com.sun.management.internal=ALL-UNNAMED \
--add-opens=java.base/java.io=ALL-UNNAMED \
--add-opens=java.base/java.nio=ALL-UNNAMED \
--add-opens=java.base/java.net=ALL-UNNAMED \
--add-opens=java.base/java.util=ALL-UNNAMED \
--add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
--add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED \
--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED \
--add-opens=java.base/java.lang=ALL-UNNAMED \
--add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
--add-opens=java.base/java.math=ALL-UNNAMED \
--add-opens=java.sql/java.sql=ALL-UNNAMED \
--add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
--add-opens=java.base/java.time=ALL-UNNAMED \
--add-opens=java.base/java.text=ALL-UNNAMED \
--add-opens=java.management/sun.management=ALL-UNNAMED \
--add-opens=java.desktop/java.awt.font=ALL-UNNAMED \
-cp "./lib/*" com.norconex.crawler.fs.FsCrawler "$@"
