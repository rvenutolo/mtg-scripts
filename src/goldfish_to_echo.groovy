#!/usr/bin/env groovy

@Grab(group = 'org.apache.commons', module = 'commons-csv', version = '1.6')

import groovy.transform.Immutable
import groovy.transform.Sortable
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.apache.commons.csv.CSVRecord

@Immutable
@Sortable
class Entry {
    String name
    String setCode
    String setName
    int quantity
    boolean isFoil
}

static void printNotImportedEntries(final String message, final List<Entry> entries) {
    System.err.println('-' * 80)
    System.err.println(message)
    System.err.println('')
    entries.each { System.err.println("${it.name}${it.isFoil ? ' (foil)' : ''} - ${it.setCode} - ${it.quantity}") }
    System.err.println('')
}

if (args && args.size() != 1) {
    throw new IllegalArgumentException("Expected at most one argument: ${args}")
}


// Read MTGGoldfish entries to import to EchoMTG

final InputStream inputStream = args ? new File(args[0]).newInputStream() : System.in

final List<Entry> entries = []

inputStream.withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(reader).each { final CSVRecord csvRecord ->
        entries << new Entry(
            name: csvRecord.get('Card'),
            setCode: csvRecord.get('Set ID'),
            setName: csvRecord.get('Set Name'),
            quantity: csvRecord.get('Quantity') as int,
            isFoil: csvRecord.get('Foil') == 'FOIL'
        )
    }
}


// There are some entries I do not want to import from MTGGoldfish to EchoMTG
// Read those entries and remove them from the main list

final List<Entry> entriesToSkip = []

new File('skip_import.csv').withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withHeader('Name', 'Set', 'Quantity').parse(reader).each { final CSVRecord csvRecord ->
        entriesToSkip << new Entry(
            name: csvRecord.get('Name'),
            setCode: csvRecord.get('Set'),
            setName: '',
            quantity: csvRecord.get('Quantity') as int,
            isFoil: false // currently don't have any foils to skip
        )
    }
}

// Remove entries from main list that are in the skip list
// May need to re-adjust quantity (ex: main list has quantity of 8, but skip list has 4)
//
// Also ensure that skip list is up-to-date
// by failing if there is something in the skip list that is not in the main list

entriesToSkip.each { final Entry entryToSkip ->
    final List<Entry> matchingEntries = entries.findAll {
        it.name == entryToSkip.name && it.setCode == entryToSkip.setCode
    }
    if (matchingEntries.empty) {
        throw new IllegalStateException(
            "Entry in skip file, but not in MTGGoldfish collection: ${entryToSkip.name}/${entryToSkip.setCode}"
        )
    } else if (matchingEntries.size() > 1) {
        throw new IllegalStateException(
            "Matched entry in skip file more than once: ${entryToSkip.name}/${entryToSkip.setCode} -- ${matchingEntries}"
        )
    }
    final Entry matchingEntry = matchingEntries.first()
    if (matchingEntry.quantity < entryToSkip.quantity) {
        throw new IllegalStateException(
            "MTGGoldfish collection does not contain at least ${entryToSkip.quantity} ${entryToSkip.name}/${entryToSkip.setCode}"
        )
    } else if (matchingEntry.quantity == entryToSkip.quantity) {
        // just remove it
        entries.remove(matchingEntry)
    } else {
        // remove existing matching entry and add new one with updated quantity
        entries.remove(matchingEntry)
        entries.add(
            new Entry(
                name: matchingEntry.name,
                setCode: matchingEntry.setCode,
                setName: matchingEntry.setName,
                quantity: matchingEntry.quantity - entryToSkip.quantity,
                isFoil: false
            )
        )
    }
}


// There are a number of cards that have multiple artworks in the same set
// Ex: Hymn to Tourach and High Tide in Fallen Empires
// The MTGGoldfish CSV does not distinctly identify these
// Collect these for later output and remove them from main list

final Map<String, List<String>> cardsWithMultipleArtworks = [:].withDefault { [] }

new File('multiple_arts.csv').withReader('UTF-8') { final Reader reader ->
    CSVFormat.DEFAULT.withHeader('Set', 'Name').parse(reader).each { final CSVRecord csvRecord ->
        cardsWithMultipleArtworks[csvRecord.get('Set').trim()] << csvRecord.get('Name').trim()
    }
}

final List<Entry> entriesWithMultipleArtworks = entries.findAll {
    it.setCode in cardsWithMultipleArtworks.keySet() && it.name in cardsWithMultipleArtworks[it.setCode]
}
entries.removeAll(entriesWithMultipleArtworks)


// EchoMTG CSV import does not handle split cards
// Collect these entries for output later and remove them from main list

final List<Entry> splitCardEntries = entries.findAll { it.name.contains('/') }
entries.removeAll(splitCardEntries)


// Output entries to import to EchoMTG to stdout

CSVFormat.DEFAULT.printer().withCloseable { final CSVPrinter csvPrinter ->
    csvPrinter.printRecord('Reg Qty', 'Foil Qty', 'Name', 'Set', 'Acquired', 'Language')
    entries.toSorted().each { final Entry entry ->
        csvPrinter.printRecord(
            entry.isFoil ? 0 : entry.quantity,
            entry.isFoil ? entry.quantity : 0,
            entry.name,
            entry.setCode,
            '',
            'EN'
        )
    }
}


// Output entries I need to handle manually to stderr

printNotImportedEntries('Cards with multiple artworks in set', entriesWithMultipleArtworks)
printNotImportedEntries('Split cards that cannot be imported to Echo', splitCardEntries)


// Output skipped entries to stderr

printNotImportedEntries('Cards skipped in import', entriesToSkip)
