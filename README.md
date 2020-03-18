This starts up an XSLT debugger session that speaks the [Protocol]().

Start up [com.philschatz.xslt.App](./com.philschatz.xslt/src/main/java/com/philschatz/xslt/App.java) and then use [vscode-xslt-debug](https://github.com/philschatz/vscode-xslt-debug) to set breakpoints and launch the debugger.

# Building

```
cd com.philschatz.xslt
mvn package assembly:single
```