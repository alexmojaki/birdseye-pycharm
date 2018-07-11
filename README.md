# ![logo](https://i.imgur.com/i7uaJDO.png) birdseye plugin for PyCharm

[JetBrains Plugin Repository page](https://plugins.jetbrains.com/plugin/10917-birdseye)

[birdseye](https://github.com/alexmojaki/birdseye) is a unique debugger for Python. This plugin lets you use it right in the code editor of your Intellij IDE, so that you can switch between editing and debugging seamlessly:

![demo](https://i.imgur.com/xJQzXWe.gif)

By default the plugin also runs the birdseye server for you, although you can configure it for total freedom.

You can switch between using the plugin and the normal browser UI for birdseye without any additional effort; both use the same database and server.

## Basic usage

1. As you would do with normal birdseye, decorate your function with the `@eye` decorator and run your function however you want. If you've never used birdseye before, now is a good time to [learn how](https://github.com/alexmojaki/birdseye#installation).
2. If your decorated function was called, the birdseye logo will appear on the left by the function definition. Click on it to see a table of calls to this function.
3. Click on the call that you want to inspect in a new tab. If there is only one call, it will automatically be selected, i.e. this step is done for you.
4. You can now hover over expressions in your code and see their values.
5. Click on expressions to expand their values in the call panel. Click on them again to deselect them, or press delete or backspace while they're selected in the call panel.
6. Click on the arrows next to loops to step back and forth through iterations. The number by the arrows is the 0-based index of the iteration.
7. To temporarily hide debugging information, minimise the tool window.

## Further notes

1. Unlike the regular birdseye UI, functions and calls are not based on the names of functions or files. Clicking the eye icon shows a list of calls to a function with that exact *body*. The file containing the function doesn't matter, and editing the function and rerunning it leads to a new list of calls. This is so that the debugging can happen right in the editor.
2. You can edit a function while you're busy inspecting it, but you will usually no longer be able to see the values of the expressions whose code changes.
3. Inspecting a call can take a lot of memory, so close call panels when you're done with them.
4. birdseye needs a server to connect to. By default the plugin will run the server for you. To configure this, go to the birdseye section of Preferences. Note that the database URL setting corresponds to the [`BIRDSEYE_DB` environment variable](https://github.com/alexmojaki/birdseye#configuration). The server needs to run at least version 0.5.0 of birdseye.
