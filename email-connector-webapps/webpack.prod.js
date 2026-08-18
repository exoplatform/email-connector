const path = require('path');
const ESLintPlugin = require('eslint-webpack-plugin');
const { VueLoaderPlugin } = require('vue-loader');

const config = {
  context: path.resolve(__dirname, '.'),
  mode: 'production',
  module: {
    rules: [
      {
        test: /\.js$/,
        exclude: /node_modules/,
        use: [
          'babel-loader',
        ]
      },
      {
        test: /\.vue$/,
        use: [
          'vue-loader',
        ]
      }
    ]
  },
  entry: {
    emailConnectorAdministration : './src/main/webapp/vue-apps/email-connector-administration/main.js',
    emailConnectorUserSetting : './src/main/webapp/vue-apps/email-connector-user-setting/main.js',
    emailConnectorCommon : './src/main/webapp/vue-apps/email-connector-common/main.js',
    emailConnectorMailBox: './src/main/webapp/vue-apps/email-connector-mail-box/main.js',
    emailConnectorContacts: './src/main/webapp/vue-apps/email-connector-contacts/main.js',
    emailConnectorNotificationExtension: './src/main/webapp/vue-apps/email-connector-notification-extension/main.js',
    emailConnectorDocumentsExtension: './src/main/webapp/vue-apps/email-connector-documents-extension/main.js',
    emailConnectorFavoriteDrawerExtension: './src/main/webapp/vue-apps/email-connector-favorite-drawer-extension/main.js',
    emailSearch: './src/main/webapp/vue-apps/email-connector-search/main.js',
  },
  output: {
    path: path.join(__dirname, 'target/email-connector/'),
    filename: 'js/[name].bundle.js',
    libraryTarget: 'amd'
  },
  plugins: [
    new ESLintPlugin({
      files: [
        './src/main/webapp/vue-apps/*.js',
        './src/main/webapp/vue-apps/*.vue',
        './src/main/webapp/vue-apps/**/*.js',
        './src/main/webapp/vue-apps/**/*.vue',
      ],
    }),
    new VueLoaderPlugin()
  ],
  externals: {
    vue: 'Vue',
    vuetify: 'Vuetify',
    jquery: '$',
  },
};

module.exports = config;
