#!/bin/sh

dir=`dirname $0`

# Forward -D options to the JVM as real system properties, in addition to
# passing them to ant as project properties. Ant's -D only sets project
# properties: System.getProperty in this (the builder) JVM is what bake-time
# readers consult (proven 2026-10-09: ant -D gives project.prop=[false] but
# system.prop=[null], so -Djnode.l2.inline=false silently did nothing).
# The ant side must keep receiving them too: it feeds the generated
# org.jnode.vm.compiler.CompilerFlags template tokens (the compile-path
# flags now read those constants, not properties) and task attributes like
# jnode.compiler / my-conf.dir (bootimage task).
javaprops=""
for arg in "$@"; do
    case "$arg" in
        -D*) javaprops="$javaprops $arg" ;;
    esac
done

java -Xmx768M -Xms256M $javaprops -jar $dir/core/lib/ant-launcher.jar -lib $JAVA_HOME/lib -lib $dir/core/lib -f $dir/all/build.xml "$@"
