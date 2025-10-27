const path = require('path');
const { merge } = require('webpack-merge');

const webpackProductionConfig = require('./webpack.prod.js');

module.exports = merge(webpackProductionConfig, {
  mode: 'development',
  output: {
    path: 'E:/eXo/Binairies/platform-7.2.x-experience-SNAPSHOT/webapps/email-connector/',
    filename: 'js/[name].bundle.js'
  }
});
