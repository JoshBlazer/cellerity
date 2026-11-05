.PHONY: build test format corpus eval crosscheck dist

build:
	./gradlew build

test:
	./gradlew test

format:
	./gradlew spotlessApply

corpus:
	bash corpus/download.sh

# M0 reports load results only. Parse rate, coverage, and fidelity arrive with M1 and M2.
eval: corpus
	./gradlew :cellerity-eval:run --args="--corpus corpus/downloads --corpus corpus/samples --out eval/results/latest-corpus-load.json"

# Compares our reader with Apache POI, then proves the corpus was not modified by doing so.
crosscheck: corpus
	./gradlew :cellerity-eval:crossCheck -PcrossCheckArgs="corpus/downloads"
	bash corpus/download.sh --verify-only

dist:
	./gradlew :cellerity-cli:installDist
