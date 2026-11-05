.PHONY: build test format corpus eval crosscheck dist

build:
	./gradlew build

test:
	./gradlew test

format:
	./gradlew spotlessApply

corpus:
	bash corpus/download.sh

# One run per corpus, because their numbers mean different things (ADR-0008, ADR-0013). Each run fails
# if it falls below its baseline, so a metric can only regress by editing that file on purpose.
eval: corpus
	./gradlew :cellerity-eval:run --args="--corpus corpus/downloads/poi --corpus corpus/samples --out eval/results/latest-corpus-load.json --baseline eval/baseline-poi.properties"
	./gradlew :cellerity-eval:run --args="--corpus corpus/downloads/enron-nuix --out eval/results/latest-enron.json --baseline eval/baseline-enron.properties"

# Compares our reader with Apache POI, then proves the corpus was not modified by doing so.
crosscheck: corpus
	./gradlew :cellerity-eval:crossCheck -PcrossCheckArgs="corpus/downloads"
	bash corpus/download.sh --verify-only

dist:
	./gradlew :cellerity-cli:installDist
