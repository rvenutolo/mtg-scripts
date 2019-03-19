#!/usr/bin/env groovy

@Grab(group = 'org.apache.commons', module = 'commons-csv', version = '1.6')

import groovy.transform.Canonical
import groovy.transform.Sortable
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.apache.commons.csv.CSVRecord

@Canonical
@Sortable
class Card {
    String name
    String setName
    String setCode
    boolean isFoil
}

if (args?.size() != 2) {
    throw new IllegalArgumentException("Expected two arguments")
}

final File goldfishFile = new File(args[0])
final File echoFile = new File(args[1])

// Read MTGGoldfish collection data

final List<Tuple2<Card, Integer>> goldfishList = []

goldfishFile.withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(reader).each { final CSVRecord csvRecord ->
        final Card card = new Card(
            name: csvRecord.get('Card'),
            setName: csvRecord.get('Set Name'),
            setCode: csvRecord.get('Set ID'),
            isFoil: csvRecord.get('Foil') == 'FOIL'
        )
        final int quantity = csvRecord.get('Quantity') as int
        goldfishList << new Tuple2<Card, Integer>(card, quantity)
    }
}


// MTGGoldfish and EchoMTG do not use all the same set names and codes
// Read data to convert MTGGoldfish set names and codes to those used by EchoMTG

final Map<Tuple2<String, String>, Tuple2<String, String>> goldfishToEchoSets = [:]

new File('goldfish_to_echo_sets.csv').withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withHeader(
        'Goldfish Name', 'Goldfish Code', 'Echo Name', 'Echo Code'
    ).parse(reader).each { final CSVRecord csvRecord ->
        final Tuple2<String, String> goldfishSet =
            new Tuple2<>(csvRecord.get('Goldfish Name'), csvRecord.get('Goldfish Code'))
        final Tuple2<String, String> echoSet =
            new Tuple2<>(csvRecord.get('Echo Name'), csvRecord.get('Echo Code'))
        goldfishToEchoSets[goldfishSet] = echoSet
    }
}


// Update MTGGoldfish set names and codes as necessary

goldfishList.collect { it.first }.each {
    final Tuple2<String, String> goldfishSet = new Tuple2<>(it.setName, it.setCode)
    if (goldfishSet in goldfishToEchoSets.keySet()) {
        final Tuple2<String, String> echoSet = goldfishToEchoSets[goldfishSet]
        it.setName = echoSet.first
        it.setCode = echoSet.second
    }
}


// Read the EchoMTG set data to use in validation of set names and codes

final Map<String, String> echoSets = [:]

new File('echo_sets.csv').withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withHeader('Name', 'Code').parse(reader).each { final CSVRecord csvRecord ->
        final String setName = csvRecord.get('Name')
        final String setCode = csvRecord.get('Code')
        if (echoSets.keySet().contains(setName)) {
            // this is to catch multiple 'Revised' sets (original English white border and foreign black border)
            throw new IllegalArgumentException("Multiple sets with name: ${setName}")
        }
        echoSets[setCode] = setName
    }
}


// Find entries with set names and codes not in EchoMTG set data and fail if there are any

final Set<Tuple2<String, String>> badGoldfishSets =
    goldfishList.collect{ it.first }.findAll {
        !(it.setCode in echoSets.keySet()) || (it.setName != echoSets[it.setCode])
    }.collect {
        new Tuple2<String, String>(it.setCode, it.setName)
    }.toSet()

if (badGoldfishSets) {
    final String badSetsString = badGoldfishSets.collect { "${it.second},${it.first}" }.sort().join('\n')
    throw new IllegalArgumentException("MTGGoldfish sets not in EchoMTG set data:\n${badSetsString}")
}
