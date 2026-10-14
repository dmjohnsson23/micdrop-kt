// args: <people csv> <output file>
val rows = mutableListOf<Collector>()

migrate(CsvSource.open(java.nio.file.Path.of(args[0])), rows.asSink()) {
    take("First Name").put("first")
    take("Last Name") {
        convert { (it as String).uppercase() }
        put("last")
    }
}

java.io.File(args[1]).writeText("${rows.size}")
