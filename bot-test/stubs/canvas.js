'use strict'
// Stand-in for the native 'canvas' module, used only so prismarine-viewer can load on the server.
module.exports = {
  createCanvas () {
    throw new Error('canvas is stubbed in this test setup (headless rendering is not available)')
  }
}
