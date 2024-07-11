#!/bin/bash

if ! ./build.sh; then
    echo "Bob der Baumeister failed to build"
    exit 1
fi

SECONDS=0
for i in {1..300}
do
    echo "Iteration $i"

	java -cp "bin:bin/de/codecoverage/config/*" de.codecoverage.config.TestDriver 3 > output.log 2>&1

    # Überprüfen auf "Exception" => Failed !
    if grep -q "Exception" output.log; then
        echo "Fehler im Iteration $i."
        exit 1
    fi
done
duration=$SECONDS
echo "Okay ... took $((duration / 60)) minutes and $((duration % 60)) seconds."
echo "Okay"
