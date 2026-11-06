.PHONY: build test format corpus eval fidelity fidelity-enron fidelity-poi recalc crosscheck dist

FIDELITY_HEAP ?= 2g

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

# Compares calculated values with the values stored in the files. Output is aggregates only (ADR-0013).
fidelity: fidelity-enron fidelity-poi

fidelity-enron: corpus
	./gradlew :cellerity-eval:fidelity -PfidelityHeap=$(FIDELITY_HEAP) -PfidelityArgs="--corpus corpus/downloads/enron-nuix --out eval/results/latest-fidelity-enron.json --report eval/results/latest-report-enron.html --baseline eval/baseline-fidelity-enron.properties"

fidelity-poi: corpus
	./gradlew :cellerity-eval:fidelity -PfidelityHeap=$(FIDELITY_HEAP) -PfidelityArgs="--corpus corpus/downloads/poi --corpus corpus/samples --out eval/results/latest-fidelity-poi.json --report eval/results/latest-report-poi.html --detail eval/results/latest-detail-poi.tsv --baseline eval/baseline-fidelity-poi.properties"

# Changes inputs on every corpus workbook, and compares incremental results with a full recalculation.
recalc: corpus
	./gradlew :cellerity-eval:recalc -PfidelityHeap=$(FIDELITY_HEAP) -PrecalcArgs="--corpus corpus/downloads/enron-nuix --corpus corpus/downloads/poi --corpus corpus/samples --min-changes 500"

# Compares our reader with Apache POI, then proves the corpus was not modified by doing so.
crosscheck: corpus
	./gradlew :cellerity-eval:crossCheck -PcrossCheckArgs="corpus/downloads"
	bash corpus/download.sh --verify-only

dist:
	./gradlew :cellerity-cli:installDist
