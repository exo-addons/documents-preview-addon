const path = require('path');
const ESLintPlugin = require('eslint-webpack-plugin');
const { VueLoaderPlugin } = require('vue-loader')

let config = {
  context: path.resolve(__dirname, '.'),
  // set the entry point of the application
  // can use multiple entry
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
  entry: {
    documentsPreviewPDF: './src/main/webapp/vue-apps/documents-pdf-preview/main.js',
  },
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
      },
      {
        test: /\.scss$/,
        use: [
          'vue-style-loader',
          'css-loader',
          'sass-loader'
        ]
      }
    ]
  }
};

module.exports = config;
