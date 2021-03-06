
namespace rb weari.thrift
namespace java org.cdlib.was.weari.thrift

/* Throw when we encounter a bad JSON file. */
exception BadJSONException {
  1: string message,
  2: string arcname
}

exception IndexException {
  1: string message
}

exception UnparsedException {
  1: string arcname
}

exception ParseException {
  1: string message
}

service Server {
  void index(1: list<string> arcs,
             2: string extraId,
             3: map<string,list<string>> extraFields)
    throws (1: IndexException ex1, 2: UnparsedException ex2, 3: BadJSONException ex3);

  void remove (1: list<string> arcs)
    throws (1: IndexException ex1);

  void parseArcs(1: list<string> arcs)
    throws (1: ParseException ex1);

  bool isArcParsed(1: string arc);
  
  void deleteParse(1: string arc);

  void setFields(1: string query,
                 2: map<string,list<string>> fieldList);
}
