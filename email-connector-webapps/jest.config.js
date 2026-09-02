/*
 * Copyright (C) 2025 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

// Jest is a separate toolchain from the webpack production build (webpack.prod.js /
// webpack.watch.js), which runs babel-loader with no babel.config.js of its own — the
// source is plain ES modules that webpack 5 and every supported browser already
// understand. The babel preset below is passed INLINE, through this file's `transform`
// option, on purpose: dropping a babel.config.js at the project root would also be
// picked up by babel-loader and silently change what the production bundle contains.
// Jest needs its own transform only because it runs the source directly under Node.
const babelConfig = {
  presets: [['@babel/preset-env', { targets: { node: 'current' } }]],
};

module.exports = {
  testEnvironment: 'jsdom',
  setupFiles: ['./jest.setup.js'],
  moduleFileExtensions: ['js', 'vue', 'json'],
  transform: {
    '^.+\\.vue$': 'vue-jest',
    '^.+\\.js$': ['babel-jest', babelConfig],
  },
  // vue-jest reads its own babel config from here (an inline object, rather than a
  // babelConfig: true file lookup) — see the comment above: no babel.config.js is
  // dropped at the project root, so babel-loader's production build stays untouched.
  globals: {
    'vue-jest': { babelConfig },
  },
  testMatch: ['**/__tests__/**/*.spec.js'],
  testPathIgnorePatterns: ['/node_modules/', '/target/'],
};
