.PHONY: build test format corpus eval dist

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

dist:
	./gradlew :cellerity-cli:installDist
